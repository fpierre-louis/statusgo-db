package io.sitprep.sitprepapi.service;

import io.sentry.Sentry;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.GroupAlertFrame;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.util.GroupUrlUtil;
import io.sitprep.sitprepapi.util.GroupNotificationRecipients;
import io.sitprep.sitprepapi.service.PushPolicyService.Category;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * <p><b>Live-broadcast:</b> auto-clears broadcast after commit on
 * {@code /topic/group/{id}/status}, using the same frame as manual
 * alert flips so connected clients dismount crisis UI immediately.</p>
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
    private final WebSocketMessageSender webSocketMessageSender;

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
                                  NotificationService notificationService,
                                  WebSocketMessageSender webSocketMessageSender) {
        this.groupRepo = groupRepo;
        this.householdEventService = householdEventService;
        this.userInfoRepo = userInfoRepo;
        this.notificationService = notificationService;
        this.webSocketMessageSender = webSocketMessageSender;
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
        List<GroupAlertFrame> frames = new ArrayList<>();
        for (Group g : stale) {
            g.setAlert(null);
            g.setActiveHazardType(null);
            g.setAlertActivatedAt(null);
            g.setCheckInRemindersFired(0);
            g.setUpdatedAt(now);
            frames.add(new GroupAlertFrame(
                    g.getGroupId(),
                    "Cleared",
                    null,
                    "system",
                    "decay"
            ));

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
            // user). When the auto-decay fires, admins/owners get a prompt
            // asking whether they want to keep the check-in going. Tapping
            // the deep link routes them to the group surface where they can
            // re-flip alert to Active.
            try {
                notifyContinuePrompt(g);
            } catch (Exception inner) {
                log.warn("GroupAlertDecay: failed continue-prompt for group {}: {}",
                        g.getGroupId(), inner.getMessage());
            }
        }

        groupRepo.saveAll(stale);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                for (GroupAlertFrame frame : frames) {
                    webSocketMessageSender.sendGroupAlertStatus(frame.groupId(), frame);
                }
            }
        });
        return stale.size();
    }

    /**
     * Fan out the "Check-in ended after 48h — continue?" prompt to
     * admins/owners of an auto-decayed group.
     */
    private void notifyContinuePrompt(Group group) {
        List<String> recipientEmails = GroupNotificationRecipients.adminOwnerEmails(group);
        if (recipientEmails.isEmpty()) return;

        String owner = group.getOwnerName() != null ? group.getOwnerName() : "your group leader";
        String title = group.getGroupName();
        String body = "Check-in auto-ended after 48 hours. Tap to continue if family still needs it.";
        String type = "checkin_auto_ended";
        String referenceId = group.getGroupId();
        String targetUrl = GroupUrlUtil.getGroupTargetUrl(group);

        List<UserInfo> users = userInfoRepo.findByUserEmailIn(recipientEmails);
        for (UserInfo user : users) {
            String token = user.getFcmtoken();
            notificationService.deliverPresenceAwareForGroup(
                    user.getUserEmail(),
                    title,
                    body,
                    owner,
                    "/images/group-alert-icon.png",
                    type,
                    referenceId,
                    targetUrl,
                    null,
                    token,
                    group.getGroupId(),
                    Category.CHECK_IN_REVIEW
            );
        }
        log.info("GroupAlertDecay: continue-prompt fanned out to {} admins/owners of group {}",
                users.size(), group.getGroupId());
    }
}
