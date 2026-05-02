package io.sitprep.sitprepapi.service;

import io.sentry.Sentry;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.util.GroupUrlUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Quarter-hourly sweep that auto-clears group alerts an admin forgot
 * about. Implements item #2 of {@code docs/SCHEDULED_JOBS.md}.
 *
 * <p>{@code Group.alert == "Active"} is set by an admin tap and is only
 * cleared by another admin tap. If the admin forgets, the household /
 * org sits in alert mode indefinitely — every {@code MeContext} rollup
 * inherits the staleness, every {@code CrisisBand} consumer in the FE
 * paints a red banner over data that's days old, and the
 * {@code activeGroupAlertCounts} badge stays lit. This decay service
 * is the safety net for that.</p>
 *
 * <p><b>Decay rule:</b> a group is auto-cleared when
 * {@code alert == "Active"} AND {@code alertActivatedAt} is older than
 * {@code app.groupAlert.decayHours} (default 72h). We use
 * {@code alertActivatedAt} (set in {@code GroupService} on the
 * Active-flip) instead of {@code updatedAt} because the latter is
 * touched by every group edit (member join, name change, post) — a
 * chatty group could keep its alert alive forever if we used
 * {@code updatedAt}.</p>
 *
 * <p><b>Pre-deploy backlog:</b> existing Active alerts at the moment
 * this service first ships have null {@code alertActivatedAt} and are
 * excluded. They'll need a manual flip to start being auto-decayable.
 * That's intentional — the alternative (backfilling the column from
 * {@code updatedAt} or {@code createdAt}) risks surprise mass-clears
 * on rollout.</p>
 *
 * <p><b>Live-broadcast:</b> v1 of this service does NOT broadcast on
 * {@code /topic/groups/{id}} when it auto-clears. The manual
 * {@code alertBecameInactive} path doesn't broadcast either (only the
 * Active-flip fans out push) — connected clients see the change on
 * their next refresh / WS reconnect / poll. If real-time decay
 * reflection becomes a real ask, both paths should add the same
 * broadcast in {@code GroupService} and we shouldn't fork them here.</p>
 *
 * <p><b>Household event:</b> for groups of type Household, we record
 * a {@code checkin-ended} event with {@code actorEmail = null} so the
 * household timeline reflects the auto-resolution. {@link
 * io.sitprep.sitprepapi.domain.HouseholdEvent} treats null actor as
 * "system-generated", which is exactly right here.</p>
 */
@Service
public class GroupAlertDecayService {

    private static final Logger log = LoggerFactory.getLogger(GroupAlertDecayService.class);

    private final GroupRepo groupRepo;
    private final HouseholdEventService householdEventService;
    private final UserInfoRepo userInfoRepo;
    private final NotificationService notificationService;

    /**
     * Threshold for the auto-decay sweep. Default is 48h, matching
     * the user's "auto-reset after 48h if not ended" requirement
     * (decided 2026-05-03; was 72h pre-redesign). Configurable via
     * env var so a future tuning pass doesn't require a code change.
     */
    @Value("${app.groupAlert.decayHours:48}")
    private int decayHours;

    @Value("${app.groupAlert.sweepBatchSize:100}")
    private int sweepBatchSize;

    public GroupAlertDecayService(GroupRepo groupRepo,
                                  HouseholdEventService householdEventService,
                                  UserInfoRepo userInfoRepo,
                                  NotificationService notificationService) {
        this.groupRepo = groupRepo;
        this.householdEventService = householdEventService;
        this.userInfoRepo = userInfoRepo;
        this.notificationService = notificationService;
    }

    /**
     * Quarter-hourly tick. {@code initialDelay} of 5min keeps decay
     * work off the boot path while the rest of the bean graph wires
     * up — and gives us a window to ship hotfixes if the threshold
     * lands wrong without immediately mass-clearing groups.
     */
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT5M")
    public void scheduledDecaySweep() {
        try {
            int decayed = decayOnce();
            if (decayed > 0) {
                log.info("GroupAlertDecay: cleared {} stale alerts (threshold={}h, batch={})",
                        decayed, decayHours, sweepBatchSize);
            } else {
                log.debug("GroupAlertDecay: nothing to decay");
            }
        } catch (Exception e) {
            log.warn("GroupAlertDecay: tick failed: {}", e.getMessage(), e);
            try { Sentry.captureException(e); } catch (Throwable ignored) {}
        }
    }

    /**
     * Run one decay pass. Public for testability + admin-triggered
     * out-of-band runs. Returns the number of groups whose alert was
     * cleared (0 when nothing was eligible).
     */
    @Transactional
    public int decayOnce() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(decayHours));
        List<Group> stale = groupRepo.findStaleActiveAlerts(cutoff, PageRequest.of(0, sweepBatchSize));
        if (stale.isEmpty()) return 0;

        Instant now = Instant.now();
        for (Group g : stale) {
            g.setAlert(null);
            g.setActiveHazardType(null);
            g.setAlertActivatedAt(null);
            g.setCheckInRemindersFired(0);
            g.setUpdatedAt(now);

            if (HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(g.getGroupType())) {
                try {
                    householdEventService.recordCheckinEnded(g.getGroupId(), null);
                } catch (Exception inner) {
                    // A timeline write failure shouldn't roll back the
                    // alert clear — the alert clear is the user-visible
                    // safety win. Log + continue.
                    log.warn("GroupAlertDecay: failed to record checkin-ended for household {}: {}",
                            g.getGroupId(), inner.getMessage());
                }
            }

            // "Continue check-in?" notification (added 2026-05-03 per
            // user). When the auto-decay fires, every member of the
            // group gets a push asking whether they want to keep the
            // check-in going. Tapping the deep link routes them to
            // the group surface where an admin can re-flip alert to
            // Active. Non-admins see the same prompt but their tap
            // just opens the surface (no admin controls).
            try {
                notifyContinuePrompt(g);
            } catch (Exception inner) {
                log.warn("GroupAlertDecay: failed continue-prompt for group {}: {}",
                        g.getGroupId(), inner.getMessage());
            }
        }

        groupRepo.saveAll(stale);
        return stale.size();
    }

    /**
     * Fan out the "Check-in ended after 48h — continue?" prompt to
     * every member of an auto-decayed group. Mirrors the shape of
     * {@link NotificationService#notifyGroupAlertChange}.
     */
    private void notifyContinuePrompt(Group group) {
        List<String> memberEmails = group.getMemberEmails();
        if (memberEmails == null || memberEmails.isEmpty()) return;

        String owner = group.getOwnerName() != null ? group.getOwnerName() : "your group leader";
        String title = group.getGroupName();
        String body = "Check-in auto-ended after 48 hours. Tap to continue if family still needs it.";
        String type = "checkin_auto_ended";
        String referenceId = group.getGroupId();
        String targetUrl = GroupUrlUtil.getGroupTargetUrl(group);

        List<UserInfo> users = userInfoRepo.findByUserEmailIn(memberEmails);
        for (UserInfo user : users) {
            String token = user.getFcmtoken();
            notificationService.deliverPresenceAware(
                    user.getUserEmail(),
                    title,
                    body,
                    owner,
                    "/images/group-alert-icon.png",
                    type,
                    referenceId,
                    targetUrl,
                    null,
                    token
            );
        }
        log.info("GroupAlertDecay: continue-prompt fanned out to {} members of group {}",
                users.size(), group.getGroupId());
    }
}
