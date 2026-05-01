package io.sitprep.sitprepapi.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Defensive rate limiter for the un-authed
 * {@code POST /api/plans/activations/{id}/acks} endpoint. Per
 * {@code docs/LAUNCH_READINESS.md} "Server-side auth gate" section:
 *
 * <blockquote>
 *   Plan recipients may not have a SitPrep account, so the ack
 *   endpoint stays un-authed. Means anyone with the opaque activationId
 *   can spam acks. Pre-launch defense: rate-limit acks by
 *   (activationId, IP). Long-term: per-recipient token in the share
 *   URL so each recipient has their own gate.
 * </blockquote>
 *
 * <p>Cap: 10 acks per minute per (activationId, remote IP). Legitimate
 * recipients ack 1–3 times during a single activation (safe → maybe
 * shift to needs-help → maybe pickup), so 10/minute is well above the
 * real ceiling while still rejecting a burst-spammer. The window slides;
 * older timestamps are pruned lazily on each call.</p>
 *
 * <p>In-memory only — same posture as {@link RateLimiterService} for
 * push throttling. Per-pod state. Pod restart resets counters: that's
 * fine for a defensive cap (worst case is a one-time burst right after
 * restart, identical fail-open behavior to the FCM rate caps).</p>
 *
 * <p>The {@link #key} construction uses the request's resolved client
 * IP (X-Forwarded-For first hop on Heroku; otherwise the socket peer).
 * IPv6 traffic shares the same key shape — no special-casing needed.</p>
 */
@Service
public class AckRateLimiter {

    /** Hard cap per (activationId, IP) per window. */
    static final int MAX_ACKS_PER_WINDOW = 10;

    /** Sliding-window length in millis. 60s matches the "10 per minute" cap. */
    static final long WINDOW_MS = 60_000L;

    /**
     * Per-key timestamp deques. Synchronized at the deque level so two
     * concurrent requests from the same IP don't race the prune+add.
     * The map itself is concurrent so different IPs don't contend.
     */
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /**
     * Try to consume a slot. Returns {@code true} when the request is
     * allowed (and records the hit), {@code false} when the rate cap
     * for this {@code (activationId, ip)} pair is exceeded.
     *
     * <p>Fail-open: any unexpected throw inside the limiter returns
     * {@code true} so a transient bug doesn't lock real recipients out
     * of acking. Spammers get a small window of pre-fix grace; that's
     * acceptable here.</p>
     */
    public boolean tryConsume(String activationId, String ip) {
        String k = key(activationId, ip);
        if (k == null) return true; // unknown caller, can't rate-limit
        try {
            Deque<Long> deque = hits.computeIfAbsent(k, x -> new ArrayDeque<>());
            long now = Instant.now().toEpochMilli();
            long cutoff = now - WINDOW_MS;
            synchronized (deque) {
                // Prune stale timestamps. Lazy — keeps the limiter
                // memory-bounded without a background sweep thread.
                Iterator<Long> it = deque.iterator();
                while (it.hasNext() && it.next() < cutoff) {
                    it.remove();
                }
                if (deque.size() >= MAX_ACKS_PER_WINDOW) return false;
                deque.addLast(now);
                return true;
            }
        } catch (Exception e) {
            // Fail-open per class doc.
            return true;
        }
    }

    private static String key(String activationId, String ip) {
        if (activationId == null || activationId.isBlank()) return null;
        if (ip == null || ip.isBlank()) ip = "unknown";
        return activationId + "|" + ip;
    }
}
