package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.service.PushPolicyService.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-recipient push rate limiting per
 * {@code docs/PUSH_NOTIFICATION_POLICY.md} "Rate limiting" + the v1
 * implementation decision (in-memory ConcurrentHashMap; no Redis).
 *
 * <p><b>Three buckets</b> all checked on each {@link #tryConsume} call:</p>
 * <ul>
 *   <li>{@link #PER_SOURCE_WINDOW} (60s) per (recipient, category) — caps
 *       at 1. Prevents two FCM pushes from a fast NWS upgrade or a rapid
 *       sequence of activation acks from the same recipient.</li>
 *   <li>{@link #PER_RECIPIENT_5MIN_WINDOW} (5min) per recipient across
 *       categories — caps at 4. Hard ceiling to absorb event bursts.</li>
 *   <li>{@link #PER_RECIPIENT_DAY_WINDOW} (24h) per recipient — caps at
 *       20 (soft). Excess drops to Lane B; the FE could surface a
 *       "X more alerts today" digest line in v2.</li>
 * </ul>
 *
 * <p><b>Fail-open</b> by design — over-pushing is recoverable, missing a
 * hurricane warning is not. Any unexpected throw inside {@link #tryConsume}
 * returns {@code true} (allow). Rate caps are a UX-quality safety net,
 * not a correctness gate.</p>
 *
 * <p><b>Per-pod state</b> — counters reset on pod restart. Single web
 * dyno today; multi-dyno scaling will need a Postgres- or Redis-backed
 * implementation behind the same interface (see policy doc "Rate caps
 * — in-memory `RateLimiterService` for v1"). The swap is the contents
 * of this class, not the call sites.</p>
 *
 * <p><b>Pruning</b> happens lazily on each {@link #tryConsume} call —
 * timestamps older than the longest window (24h) get dropped before
 * the count check. No background sweep needed; pruning piggybacks on
 * the access pattern. A scheduled prune for cold keys could be added
 * later if the map gets large in production.</p>
 */
@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    private static final Duration PER_SOURCE_WINDOW = Duration.ofSeconds(60);
    private static final int PER_SOURCE_LIMIT = 1;

    private static final Duration PER_RECIPIENT_5MIN_WINDOW = Duration.ofMinutes(5);
    private static final int PER_RECIPIENT_5MIN_LIMIT = 4;

    private static final Duration PER_RECIPIENT_DAY_WINDOW = Duration.ofHours(24);
    private static final int PER_RECIPIENT_DAY_LIMIT = 20;

    /**
     * Per-recipient rolling window of recent push timestamps, used for
     * the 5min and 24h checks. Outer map is concurrent; per-key access
     * is guarded by {@code synchronized(deque)} so the prune+count+add
     * sequence is atomic per recipient.
     */
    private final ConcurrentMap<String, Deque<Instant>> perRecipient = new ConcurrentHashMap<>();

    /**
     * Per-(recipient, category) rolling window for the 60s per-source
     * check. Same locking pattern as {@link #perRecipient}.
     */
    private final ConcurrentMap<String, Deque<Instant>> perRecipientCategory = new ConcurrentHashMap<>();

    /**
     * Returns {@code true} if a Lane A push is allowed for this
     * (recipient, category) at this moment under all three rate caps,
     * AND records the send. Returns {@code false} if any cap would be
     * exceeded — caller should demote the send to Lane B per spec.
     *
     * <p>Atomic at the per-recipient level: two concurrent calls for
     * the same recipient see consistent counts (the synchronized block
     * around prune+check+add ensures only one wins when both would
     * push past the cap). Different recipients never contend.</p>
     */
    public boolean tryConsume(String recipientEmail, Category category) {
        if (recipientEmail == null || recipientEmail.isBlank() || category == null) {
            // Not enough information to rate-limit; fail open.
            return true;
        }
        try {
            String email = recipientEmail.trim().toLowerCase();
            String catKey = email + ":" + category.name();
            Instant now = Instant.now();

            // 60s per-source check first. Cheapest to fail when an event
            // source is firing in rapid succession.
            Deque<Instant> srcDeque = perRecipientCategory.computeIfAbsent(catKey, k -> new ArrayDeque<>());
            synchronized (srcDeque) {
                pruneOlderThan(srcDeque, now, PER_SOURCE_WINDOW);
                if (srcDeque.size() >= PER_SOURCE_LIMIT) {
                    log.debug("RateLimiter: per-source cap hit for {} cat={}", email, category);
                    return false;
                }
            }

            Deque<Instant> userDeque = perRecipient.computeIfAbsent(email, k -> new ArrayDeque<>());
            synchronized (userDeque) {
                // Prune to the longest window so both checks below
                // operate on a current set.
                pruneOlderThan(userDeque, now, PER_RECIPIENT_DAY_WINDOW);

                // 5min hard ceiling.
                int recent = countWithin(userDeque, now, PER_RECIPIENT_5MIN_WINDOW);
                if (recent >= PER_RECIPIENT_5MIN_LIMIT) {
                    log.debug("RateLimiter: per-recipient 5min cap hit for {}", email);
                    return false;
                }

                // 24h soft ceiling.
                if (userDeque.size() >= PER_RECIPIENT_DAY_LIMIT) {
                    log.debug("RateLimiter: per-recipient daily cap hit for {}", email);
                    return false;
                }

                // All checks passed — record the send. Per-source deque
                // was already locked + checked above; we record there too
                // under a fresh lock since we released it. Worst case the
                // racing thread sees this insert and rate-limits the next.
                userDeque.addLast(now);
            }
            synchronized (srcDeque) {
                srcDeque.addLast(now);
            }
            return true;
        } catch (Exception e) {
            // Fail open per spec — over-pushing is recoverable; missing
            // a hurricane warning is not.
            log.warn("RateLimiter: unexpected error, failing open: {}", e.getMessage());
            return true;
        }
    }

    private static void pruneOlderThan(Deque<Instant> deque, Instant now, Duration window) {
        Instant cutoff = now.minus(window);
        Iterator<Instant> it = deque.iterator();
        while (it.hasNext()) {
            if (it.next().isBefore(cutoff)) it.remove();
            else break;  // deque is FIFO; once we see a newer one, the rest are newer
        }
    }

    private static int countWithin(Deque<Instant> deque, Instant now, Duration window) {
        Instant cutoff = now.minus(window);
        int count = 0;
        Iterator<Instant> it = deque.descendingIterator();
        while (it.hasNext()) {
            Instant t = it.next();
            if (t.isBefore(cutoff)) break;
            count++;
        }
        return count;
    }
}
