package io.sitprep.sitprepapi.service;

import io.sentry.Sentry;
import io.sitprep.sitprepapi.domain.Post.PostStatus;
import io.sitprep.sitprepapi.repo.PostRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Nightly auto-archival of stale work orders. Any work order (a Post with
 * {@code kind = "task"}) that has rested in {@link PostStatus#DONE} for longer
 * than the retention window ({@code app.workorder.archiveAfterDays}, default 7)
 * is transitioned to {@link PostStatus#ARCHIVED} so the primary operational
 * dashboard stays clean while completed history remains reachable behind the
 * FE "Archive" filter.
 *
 * <p><b>Performance:</b> the transition is a single set-based bulk UPDATE
 * ({@link PostRepo#archiveStaleWorkOrders}) — the DONE set is never loaded into
 * the JVM heap. This is the whole reason the logic lives in a JPQL
 * {@code @Modifying} query rather than a read-modify-save loop.</p>
 *
 * <p><b>Ordering:</b> {@code @EnableScheduling} already lives on
 * {@code SchedulingConfig} / {@code Application}, so this cron fires without any
 * further wiring. Failures are caught + logged + Sentry-captured so a bad tick
 * cannot kill the scheduler thread — the convention shared by the other sweep
 * services (see {@code ActivationExpirySweepService}, docs/SCHEDULED_JOBS.md).</p>
 */
@Service
public class WorkOrderArchivalService {

    private static final Logger log =
            LoggerFactory.getLogger(WorkOrderArchivalService.class);

    /** Work-order discriminator on the shared {@code task} table (Post.kind). */
    private static final String WORK_ORDER_KIND = "task";

    private final PostRepo postRepo;

    @Value("${app.workorder.archiveAfterDays:7}")
    private int archiveAfterDays;

    public WorkOrderArchivalService(PostRepo postRepo) {
        this.postRepo = postRepo;
    }

    /**
     * Daily at 02:00 server time. Cron fields: {@code second minute hour
     * day-of-month month day-of-week}. Off-peak so the bulk UPDATE never
     * competes with daytime request load.
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void scheduledArchival() {
        try {
            int count = archiveStaleWorkOrders();
            if (count > 0) {
                log.info("Auto-archived {} stale work orders.", count);
            } else {
                log.debug("WorkOrderArchival: nothing to archive");
            }
        } catch (Exception e) {
            log.warn("WorkOrderArchival: tick failed: {}", e.getMessage(), e);
            try { Sentry.captureException(e); } catch (Throwable ignored) { /* Sentry optional */ }
        }
    }

    /**
     * Run one archival pass. Public + separated from the schedule hook so an
     * admin/devops trigger can drive an out-of-band sweep without waiting for
     * the next tick, and so it is directly unit-testable.
     *
     * @return the number of work orders archived by this pass (0 when none were
     *         eligible).
     */
    @Transactional
    public int archiveStaleWorkOrders() {
        Instant now = Instant.now();
        Instant threshold = now.minus(archiveAfterDays, ChronoUnit.DAYS);
        return postRepo.archiveStaleWorkOrders(
                PostStatus.DONE, PostStatus.ARCHIVED, WORK_ORDER_KIND, threshold, now);
    }
}
