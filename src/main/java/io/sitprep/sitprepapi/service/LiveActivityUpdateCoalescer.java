package io.sitprep.sitprepapi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * At most ONE pending Live Activity update per {@code (kind, referenceId)}
 * (live_activities GAMEPLAN §3.4).
 *
 * <p>A group check-in can produce a burst of status writes: forty members
 * answering in a minute would be forty pushes to every organizer's card, and
 * priority-10 pushes draw on an hourly budget Apple does not publish. So the
 * first change for a key schedules ONE flush {@code window} later and every
 * change inside that window rides along. The flush reads the server state at
 * flush time, so nothing is lost by dropping the intermediate events: the
 * card gets the latest numbers, once.</p>
 *
 * <p>In memory, like {@link RateLimiterService}: statusgo-db runs
 * {@code web=1}. A second dyno would coalesce per dyno — at worst two updates
 * per window, never a wrong one. Revisit if it scales out.</p>
 *
 * <p>Pure apart from the injected {@link Scheduler}, so the window can be
 * tested without waiting for it.</p>
 */
public class LiveActivityUpdateCoalescer {

    private static final Logger log = LoggerFactory.getLogger(LiveActivityUpdateCoalescer.class);

    /** Runs {@code task} once, {@code delay} from now. */
    @FunctionalInterface
    public interface Scheduler {
        void schedule(Runnable task, Duration delay);
    }

    private final Scheduler scheduler;
    private final Duration window;
    private final Consumer<String> flusher;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    /**
     * @param flusher called with the key when its window closes; it reads the
     *                current state itself. Its failures are logged, never thrown.
     */
    public LiveActivityUpdateCoalescer(Scheduler scheduler, Duration window, Consumer<String> flusher) {
        this.scheduler = scheduler;
        this.window = window;
        this.flusher = flusher;
    }

    public static String key(String kind, String referenceId) {
        return kind + ":" + referenceId;
    }

    /**
     * Ask for an update. Returns true when this call opened a new window (a
     * flush was scheduled), false when one was already pending.
     */
    public boolean queue(String key) {
        if (key == null) return false;
        if (!pending.add(key)) return false;
        try {
            scheduler.schedule(() -> flush(key), window);
            return true;
        } catch (RuntimeException e) {
            // A rejected schedule (executor shut down) must not leave the key
            // stuck "pending" forever, which would silence the card for good.
            pending.remove(key);
            log.warn("LiveActivity: could not schedule an update for {}: {}", key, e.getMessage());
            return false;
        }
    }

    /**
     * Drop a pending update — an end supersedes it. Returns whether one was
     * pending. A flush already running is not interrupted; the flusher skips a
     * situation that is no longer Active.
     */
    public boolean cancel(String key) {
        return key != null && pending.remove(key);
    }

    public boolean isPending(String key) {
        return key != null && pending.contains(key);
    }

    public int pendingCount() {
        return pending.size();
    }

    private void flush(String key) {
        // Removed BEFORE the flush reads state: a change that lands while the
        // flush is running opens the next window instead of being swallowed.
        if (!pending.remove(key)) return;   // cancelled
        try {
            flusher.accept(key);
        } catch (RuntimeException e) {
            log.warn("LiveActivity: update flush failed for {}: {}", key, e.getMessage(), e);
        }
    }
}
