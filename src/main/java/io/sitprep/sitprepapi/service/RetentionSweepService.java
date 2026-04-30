package io.sitprep.sitprepapi.service;

import io.sentry.Sentry;
import io.sitprep.sitprepapi.repo.HouseholdEventRepo;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
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
 * Daily retention sweeps for high-churn log tables. Implements items #3
 * ({@code NotificationLog}) and #4 ({@code HouseholdEvent}) of
 * {@code docs/SCHEDULED_JOBS.md}.
 *
 * <p>Both tables grow one row per real-world event (push fan-out, status
 * change, check-in start, with-claim, etc.) and were never reaped. At
 * launch volumes the steady-state add rate would put the
 * {@code NotificationLog} table into the millions inside the first
 * quarter — useful for ops telemetry, useless as primary OLTP data.</p>
 *
 * <p><b>Why daily, not hourly:</b> retention sweeps don't need low
 * latency — a row that lingers an extra 23h past its cutoff doesn't
 * matter. Daily at off-peak (3:30am UTC) keeps the work off the
 * North-American business window and away from the alert ingest tick
 * (every 5min).</p>
 *
 * <p><b>Why JPQL bulk delete (not entity-load deleteAllById):</b> these
 * tables have no {@code @ElementCollection} child rows and no app-level
 * cascade to enforce, so the JPA lifecycle adds nothing — direct
 * {@code @Modifying DELETE} is faster and avoids loading entities into
 * the persistence context just to mark them for deletion.</p>
 *
 * <p><b>Postgres VACUUM caveat:</b> per {@code SCHEDULED_JOBS.md},
 * autovacuum handles steady-state reclaim, but the very first big purge
 * (when the sweep ships against an unreaped backlog) may need a manual
 * {@code VACUUM} to reclaim disk. Watch dyno disk usage after the first
 * run.</p>
 */
@Service
public class RetentionSweepService {

    private static final Logger log = LoggerFactory.getLogger(RetentionSweepService.class);

    private final NotificationLogRepo notificationLogRepo;
    private final HouseholdEventRepo householdEventRepo;

    @Value("${app.retention.notificationLogDays:30}")
    private int notificationLogRetentionDays;

    @Value("${app.retention.householdEventDays:90}")
    private int householdEventRetentionDays;

    @Value("${app.retention.sweepBatchSize:1000}")
    private int sweepBatchSize;

    public RetentionSweepService(
            NotificationLogRepo notificationLogRepo,
            HouseholdEventRepo householdEventRepo
    ) {
        this.notificationLogRepo = notificationLogRepo;
        this.householdEventRepo = householdEventRepo;
    }

    /**
     * Daily 3:30am UTC sweep of {@code NotificationLog}. Default
     * retention is 30d — push delivery telemetry doesn't earn its
     * row-bytes after a month. Long-term analytics (delivery rates,
     * type-mix, error patterns) belong in Sentry / a metrics pipeline,
     * not the primary OLTP DB.
     */
    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    public void scheduledNotificationLogSweep() {
        runSweep(
                "NotificationLogRetention",
                notificationLogRetentionDays,
                this::sweepNotificationLogOnce
        );
    }

    /**
     * Daily 3:35am UTC sweep of {@code HouseholdEvent}. 5min after
     * notif sweep so the two don't compete for DB connections /
     * autovacuum cycles. Default 90d retention — 30d felt too
     * aggressive given households actually scroll back to prior real
     * events ("did we drill last quarter?").
     */
    @Scheduled(cron = "0 35 3 * * *", zone = "UTC")
    public void scheduledHouseholdEventSweep() {
        runSweep(
                "HouseholdEventRetention",
                householdEventRetentionDays,
                this::sweepHouseholdEventOnce
        );
    }

    /**
     * Public entry points so an admin/devops endpoint or a test can
     * force a sweep without waiting for the cron tick. Returns the
     * number of rows deleted in this batch (0 when nothing was
     * eligible). One tick = one batch — successive ticks drain a
     * backlog at the configured rate.
     */
    @Transactional
    public int sweepNotificationLogOnce() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(notificationLogRetentionDays));
        List<Long> ids = notificationLogRepo.findIdsOlderThan(cutoff, PageRequest.of(0, sweepBatchSize));
        if (ids.isEmpty()) return 0;
        return notificationLogRepo.deleteByIdIn(ids);
    }

    @Transactional
    public int sweepHouseholdEventOnce() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(householdEventRetentionDays));
        List<Long> ids = householdEventRepo.findIdsOlderThan(cutoff, PageRequest.of(0, sweepBatchSize));
        if (ids.isEmpty()) return 0;
        return householdEventRepo.deleteByIdIn(ids);
    }

    private void runSweep(String jobName, int retentionDays, SweepFn fn) {
        try {
            int deleted = fn.run();
            if (deleted > 0) {
                log.info("{}: deleted {} rows (retention={}d, batch={})",
                        jobName, deleted, retentionDays, sweepBatchSize);
            } else {
                log.debug("{}: nothing to delete", jobName);
            }
        } catch (Exception e) {
            log.warn("{}: tick failed: {}", jobName, e.getMessage(), e);
            try { Sentry.captureException(e); } catch (Throwable ignored) {}
        }
    }

    @FunctionalInterface
    private interface SweepFn {
        int run();
    }
}
