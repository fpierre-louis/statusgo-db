package io.sitprep.sitprepapi.service;

import io.sentry.Sentry;
import io.sitprep.sitprepapi.repo.IdempotencyKeyRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Hourly cleanup of expired Idempotency-Key cache rows — audit P1-10.
 *
 * <p>Default TTL is 24h ({@code app.idempotency.ttlHours}). Cron fires
 * hourly at :15 to amortize the delete cost into small slices and stay
 * clear of the 3:30-3:35 UTC retention sweep window
 * ({@link RetentionSweepService}). One bulk {@code DELETE} per tick is
 * cheap because {@code idx_idem_created_at} keeps the eligibility scan
 * index-only.</p>
 */
@Service
public class IdempotencyKeySweepService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeySweepService.class);

    private final IdempotencyKeyRepo repo;

    @Value("${app.idempotency.ttlHours:24}")
    private long ttlHours;

    public IdempotencyKeySweepService(IdempotencyKeyRepo repo) {
        this.repo = repo;
    }

    @Scheduled(cron = "0 15 * * * *", zone = "UTC")
    public void scheduledSweep() {
        try {
            int deleted = sweepOnce();
            if (deleted > 0) {
                log.info("IdempotencyKeySweep: deleted {} expired rows (ttl={}h)",
                        deleted, ttlHours);
            } else {
                log.debug("IdempotencyKeySweep: nothing to delete");
            }
        } catch (Exception e) {
            log.warn("IdempotencyKeySweep tick failed: {}", e.getMessage(), e);
            try { Sentry.captureException(e); } catch (Throwable ignored) {}
        }
    }

    @Transactional
    public int sweepOnce() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(ttlHours));
        return repo.deleteOlderThan(cutoff);
    }
}
