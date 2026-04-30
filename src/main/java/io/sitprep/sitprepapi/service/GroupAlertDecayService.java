package io.sitprep.sitprepapi.service;

import io.sentry.Sentry;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.repo.GroupRepo;
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

    @Value("${app.groupAlert.decayHours:72}")
    private int decayHours;

    @Value("${app.groupAlert.sweepBatchSize:100}")
    private int sweepBatchSize;

    public GroupAlertDecayService(GroupRepo groupRepo,
                                  HouseholdEventService householdEventService) {
        this.groupRepo = groupRepo;
        this.householdEventService = householdEventService;
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
        }

        groupRepo.saveAll(stale);
        return stale.size();
    }
}
