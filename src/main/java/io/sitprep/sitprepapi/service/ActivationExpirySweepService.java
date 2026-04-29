package io.sitprep.sitprepapi.service;

import io.sentry.Sentry;
import io.sitprep.sitprepapi.repo.PlanActivationAckRepo;
import io.sitprep.sitprepapi.repo.PlanActivationRepo;
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
 * Hourly cleanup of expired {@link io.sitprep.sitprepapi.domain.PlanActivation}
 * rows and their dependent acks. Implements item #1 of
 * {@code docs/SCHEDULED_JOBS.md}.
 *
 * <p><b>Why hard-delete:</b> the FE already filters by {@code expiresAt > now}
 * (via {@code PlanActivationRepo.findFirstActiveByOwnerEmail}), so expired
 * rows are invisible to active flows but accumulate forever. At launch
 * volumes the table will outgrow its useful working set within months.
 * Soft-archive would buy a clean 410 for stale links — but that's a
 * recipient-side edge case (link arrived past expiry), not a primary flow,
 * and it's reversible to soft if we hear it.</p>
 *
 * <p><b>Grace window:</b> we don't delete the moment {@code expiresAt}
 * passes. {@code ACTIVATION_RETENTION_AFTER_EXPIRY_DAYS} (default 14) keeps
 * stale rows around so a recipient who got the link late still sees their
 * data, and so the owner's "recent activations" view (when it ships) has a
 * recent-history window beyond just live ones.</p>
 *
 * <p><b>Bounded batches:</b> each tick processes at most
 * {@code ACTIVATION_SWEEP_BATCH_SIZE} (default 200) so a backlog after a
 * long scheduler pause can't lock the table or OOM the dyno. Subsequent
 * ticks drain the rest at the same rate.</p>
 *
 * <p><b>Cascade:</b> {@code PlanActivationAck} has no DB-level FK to
 * {@code PlanActivation} (only an indexed {@code activation_id} column),
 * so cascade is enforced here in the application. Acks are deleted first,
 * then activations — JPA's entity lifecycle handles the
 * {@code @ElementCollection} child tables (household_members, contact_ids,
 * contact_group_ids) when the parent is removed.</p>
 */
@Service
public class ActivationExpirySweepService {

    private static final Logger log = LoggerFactory.getLogger(ActivationExpirySweepService.class);

    private final PlanActivationRepo activationRepo;
    private final PlanActivationAckRepo ackRepo;

    @Value("${app.activation.retentionAfterExpiryDays:14}")
    private int retentionAfterExpiryDays;

    @Value("${app.activation.sweepBatchSize:200}")
    private int sweepBatchSize;

    public ActivationExpirySweepService(
            PlanActivationRepo activationRepo,
            PlanActivationAckRepo ackRepo
    ) {
        this.activationRepo = activationRepo;
        this.ackRepo = ackRepo;
    }

    /**
     * Hourly tick. {@code initialDelayString} of 10min keeps cleanup work
     * off the boot path — startup is busy enough with @PostConstruct
     * priming (alert ingest, Firebase init, etc.).
     */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M")
    public void scheduledSweep() {
        try {
            int deleted = sweepOnce();
            if (deleted > 0) {
                log.info("ActivationExpirySweep: deleted {} activations (retention={}d, batch={})",
                        deleted, retentionAfterExpiryDays, sweepBatchSize);
            } else {
                log.debug("ActivationExpirySweep: nothing to delete");
            }
        } catch (Exception e) {
            // Catch + log + Sentry-capture so a bad tick doesn't kill the
            // scheduler thread and ops still see the failure. Per the
            // convention in docs/SCHEDULED_JOBS.md.
            log.warn("ActivationExpirySweep: tick failed: {}", e.getMessage(), e);
            try { Sentry.captureException(e); } catch (Throwable ignored) {}
        }
    }

    /**
     * Run one sweep pass. Public for testability + to allow an
     * admin/devops endpoint to trigger an out-of-band sweep without
     * waiting for the next tick. Returns the number of activations
     * deleted (0 when nothing was eligible).
     */
    @Transactional
    public int sweepOnce() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionAfterExpiryDays));
        List<String> ids = activationRepo.findIdsExpiredBefore(cutoff, PageRequest.of(0, sweepBatchSize));
        if (ids.isEmpty()) {
            return 0;
        }

        // Acks first — we have to clear the dependent rows before the
        // parent goes, since there's no DB-level cascade declared.
        int acksDeleted = ackRepo.deleteByActivationIdIn(ids);

        // Then the activations themselves. deleteAllById walks each
        // entity through the JPA lifecycle, which removes the
        // @ElementCollection child rows automatically.
        activationRepo.deleteAllById(ids);

        log.debug("ActivationExpirySweep: deleted {} acks across {} activations", acksDeleted, ids.size());
        return ids.size();
    }
}
