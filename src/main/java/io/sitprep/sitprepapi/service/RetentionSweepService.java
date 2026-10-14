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
    private final AlertHistoryService alertHistoryService;
    private final InterpretationRecorder interpretationRecorder;

    @Value("${app.retention.notificationLogDays:30}")
    private int notificationLogRetentionDays;

    @Value("${app.retention.notificationLogArchivedDays:7}")
    private int notificationLogArchivedRetentionDays;

    @Value("${app.retention.householdEventDays:90}")
    private int householdEventRetentionDays;

    /** Alert interpretation snapshots and source revisions (V110, S2 contract §6). */
    @Value("${app.retention.alertInterpretationDays:30}")
    private int alertInterpretationRetentionDays;

    @Value("${app.retention.sweepBatchSize:1000}")
    private int sweepBatchSize;

    /**
     * Ceiling on batches one tick may delete (default 50 = 50,000 rows at the
     * default batch size). A drain normally stops on its first short batch;
     * this only bites on a first run against a large unreaped backlog, and
     * keeps that run from holding a connection for an unbounded stretch. The
     * remainder waits for tomorrow's tick, and the log says so.
     */
    @Value("${app.retention.maxBatchesPerTick:50}")
    private int maxBatchesPerTick;

    public RetentionSweepService(
            NotificationLogRepo notificationLogRepo,
            HouseholdEventRepo householdEventRepo,
            AlertHistoryService alertHistoryService,
            InterpretationRecorder interpretationRecorder
    ) {
        this.notificationLogRepo = notificationLogRepo;
        this.householdEventRepo = householdEventRepo;
        this.alertHistoryService = alertHistoryService;
        this.interpretationRecorder = interpretationRecorder;
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
        sweepAlertInterpretations();
    }

    /**
     * Steps 2 and 3 of the contract's retention order (S2 §6), run straight
     * after step 1 above so the order holds whatever the scheduler does:
     * snapshots older than the window that no notification row names (and
     * whose revision is no longer listed), then revisions last seen before the
     * window that no snapshot names.
     *
     * <p>The order is what lets one tick clear a whole chain — notification
     * rows free their snapshots, snapshots free their revisions. Each step is
     * its own drain, so a failure in one is logged and the next still runs;
     * it can only take rows nothing points at.</p>
     */
    void sweepAlertInterpretations() {
        runSweep(
                "AlertInterpretationSnapshotRetention",
                alertInterpretationRetentionDays,
                () -> interpretationRecorder.sweepSnapshotsOnce(alertInterpretationRetentionDays, sweepBatchSize)
        );
        runSweep(
                "AlertSourceRevisionRetention",
                alertInterpretationRetentionDays,
                () -> interpretationRecorder.sweepRevisionsOnce(alertInterpretationRetentionDays, sweepBatchSize)
        );
    }

    /**
     * Daily 3:32am UTC sweep of archived {@code NotificationLog} rows.
     * Default retention is 7d after archive — short on purpose: when
     * the user taps the ✓ archive button on the inbox they mean
     * "I'm done with this", and we don't earn the row-bytes by
     * keeping it any longer than the standard window for "did I really
     * mean to dismiss that?" recovery. The longer all-rows sweep
     * ({@link #scheduledNotificationLogSweep}) still catches any
     * unarchived rows past 30d.
     */
    @Scheduled(cron = "0 32 3 * * *", zone = "UTC")
    public void scheduledArchivedNotificationSweep() {
        runSweep(
                "NotificationLogArchivedRetention",
                notificationLogArchivedRetentionDays,
                this::sweepArchivedNotificationsOnce
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
     * Daily 3:37am UTC sweep of {@code alert_history}. Two minutes after the
     * household-event sweep, same reason it is offset from the notification
     * one.
     *
     * <p>This table is written every couple of minutes from the national alert
     * feed and it is the highest-churn thing in the schema: measured
     * 2026-09-07, NWS issues ~1,510 distinct alerts a day, so a 30-day window
     * holds ~45,000 rows and ~135 MB. It exists specifically so it does not
     * become the third never-reaped table alongside {@code Post} and
     * {@code AlertPost} — the retention window was part of the feature, not a
     * follow-up to it.</p>
     *
     * <p>The window itself lives on {@code AlertHistoryService} rather than
     * here, because the read path clamps a caller's requested {@code days} to
     * the same number. One value, one owner: a sweep that reaped 30 days while
     * the endpoint offered 60 would answer with a silence it created itself.</p>
     */
    @Scheduled(cron = "0 37 3 * * *", zone = "UTC")
    public void scheduledAlertHistorySweep() {
        runSweep(
                "AlertHistoryRetention",
                alertHistoryService.retentionDays(),
                () -> alertHistoryService.sweepOnce(sweepBatchSize)
        );
    }

    /**
     * Public entry points so an admin/devops endpoint or a test can
     * force ONE batch without waiting for the cron tick. Returns the
     * number of rows deleted in this batch (0 when nothing was
     * eligible). The scheduled ticks call these repeatedly through
     * {@link #drain} until a short batch, so a backlog clears in one
     * tick rather than at one batch a day.
     */
    @Transactional
    public int sweepNotificationLogOnce() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(notificationLogRetentionDays));
        List<Long> ids = notificationLogRepo.findIdsOlderThan(cutoff, PageRequest.of(0, sweepBatchSize));
        if (ids.isEmpty()) return 0;
        return notificationLogRepo.deleteByIdIn(ids);
    }

    /**
     * Sweep archived NotificationLog rows whose archivedAt is older than
     * the configured window (default 7d). User-driven dismissals burn
     * their row-bytes fast — the user said "I'm done with this" when they
     * tapped ✓ archive, so we honor it.
     */
    @Transactional
    public int sweepArchivedNotificationsOnce() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(notificationLogArchivedRetentionDays));
        List<Long> ids = notificationLogRepo.findArchivedOlderThan(cutoff, PageRequest.of(0, sweepBatchSize));
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
        drain(jobName, retentionDays, fn);
    }

    /**
     * Run {@code fn} batch after batch until one comes back short (fewer than
     * {@code sweepBatchSize} rows: nothing older is left) or the per-tick cap
     * is reached.
     *
     * <p><b>Why drain, not one batch per tick:</b> one 1,000-row batch a day
     * can't keep up with a table that adds more than that. {@code alert_history}
     * alone takes ~1,510 NWS rows a day, so a single daily batch fell ~500 rows
     * further behind every day and the 30-day window was never actually
     * enforced; {@code notification_log} can outgrow it the same way. Each
     * batch is its own transaction (its own {@code @Transactional} call or the
     * repo's {@code @Modifying} delete), so a failure part-way keeps the
     * batches already committed and ends the tick.</p>
     *
     * @return the rows deleted across every batch of this tick
     */
    int drain(String jobName, int retentionDays, SweepFn fn) {
        int cap = Math.max(1, maxBatchesPerTick);
        int total = 0;
        int batches = 0;
        try {
            boolean drained = false;
            while (batches < cap) {
                int deleted = fn.run();
                batches++;
                total += deleted;
                if (deleted < sweepBatchSize) {
                    drained = true;
                    break;
                }
            }
            if (!drained) {
                log.warn("{}: hit the per-tick cap ({} batches, {} rows); the rest waits for the next tick",
                        jobName, cap, total);
            }
            if (total > 0) {
                log.info("{}: deleted {} rows in {} batch(es) (retention={}d, batch={})",
                        jobName, total, batches, retentionDays, sweepBatchSize);
            } else {
                log.debug("{}: nothing to delete", jobName);
            }
        } catch (Exception e) {
            log.warn("{}: tick failed after {} rows: {}", jobName, total, e.getMessage(), e);
            try { Sentry.captureException(e); } catch (Throwable ignored) {}
        }
        return total;
    }

    @FunctionalInterface
    interface SweepFn {
        int run();
    }
}
