package io.sitprep.sitprepapi.util;

import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * The process-wide 1-request-per-second gate for OpenStreetMap Nominatim
 * (docs/epics/infrastructure_license_audit.md §1 #6).
 *
 * <p>Nominatim's usage policy sets an <b>absolute maximum of one request per
 * second</b> for the whole application, not per caller. Every Nominatim call
 * now goes through {@code GeocodeClient} (open-items 3.4), which takes a slot
 * here first; a cold cache after a dyno restart cannot burst past it. This is
 * the one place that counts.</p>
 *
 * <p>Calls take the next free slot, spaced {@link #MIN_INTERVAL_MS} apart.
 * A caller waits for its slot when it is close; when the queue is longer than
 * {@link #MAX_WAIT_MS}, it is told no and treats that as a lookup miss. A
 * geocode is never worth holding a request thread for several seconds.</p>
 *
 * <p>Static, because the limit belongs to the process; one dyno runs one
 * process. A second dyno would need a shared counter.</p>
 */
public final class NominatimThrottle {

    /** One second plus a margin, so clock jitter never lands two calls inside one second. */
    static final long MIN_INTERVAL_MS = 1_100;
    static final long MAX_WAIT_MS = 3_000;

    private static final Object LOCK = new Object();
    private static long nextSlotMs = 0;

    private NominatimThrottle() {}

    /** Take a slot, waiting for it if it is near. False means skip this lookup. */
    public static boolean acquire() {
        return acquire(System::currentTimeMillis, NominatimThrottle::sleep);
    }

    static boolean acquire(LongSupplier nowMs, LongConsumer sleeper) {
        long wait;
        synchronized (LOCK) {
            long now = nowMs.getAsLong();
            long slot = Math.max(now, nextSlotMs);
            wait = slot - now;
            if (wait > MAX_WAIT_MS) return false;
            nextSlotMs = slot + MIN_INTERVAL_MS;
        }
        if (wait > 0) sleeper.accept(wait);
        return true;
    }

    /** Test hook: forget reserved slots. */
    static void reset() {
        synchronized (LOCK) {
            nextSlotMs = 0;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
