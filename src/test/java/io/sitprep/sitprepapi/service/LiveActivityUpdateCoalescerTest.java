package io.sitprep.sitprepapi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * live_activities GAMEPLAN §3.4: one pending update per {@code (kind,
 * referenceId)}, flushed after the window, so forty answers in a minute are
 * one push, not forty.
 */
class LiveActivityUpdateCoalescerTest {

    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<Duration> delays = new ArrayList<>();
    private final List<String> flushed = new ArrayList<>();
    private LiveActivityUpdateCoalescer coalescer;

    @BeforeEach
    void setUp() {
        coalescer = new LiveActivityUpdateCoalescer((task, delay) -> {
            scheduled.add(task);
            delays.add(delay);
        }, Duration.ofSeconds(10), flushed::add);
    }

    @Test
    void aBurstInsideTheWindowIsOneFlush() {
        String k = LiveActivityUpdateCoalescer.key("situation", "hh-1");
        assertThat(coalescer.queue(k)).isTrue();
        for (int i = 0; i < 39; i++) assertThat(coalescer.queue(k)).isFalse();

        assertThat(scheduled).hasSize(1);
        assertThat(delays).containsExactly(Duration.ofSeconds(10));
        scheduled.get(0).run();
        assertThat(flushed).containsExactly("situation:hh-1");
        assertThat(coalescer.pendingCount()).isZero();
    }

    @Test
    void keysAreIndependent() {
        coalescer.queue("situation:a");
        coalescer.queue("situation:b");
        coalescer.queue("location_share:a");
        assertThat(scheduled).hasSize(3);
    }

    @Test
    void aChangeAfterTheFlushOpensTheNextWindow() {
        coalescer.queue("situation:a");
        scheduled.get(0).run();
        assertThat(coalescer.queue("situation:a")).isTrue();
        assertThat(scheduled).hasSize(2);
    }

    @Test
    void aChangeDuringTheFlushIsNotSwallowed() {
        List<Boolean> reopened = new ArrayList<>();
        LiveActivityUpdateCoalescer[] self = new LiveActivityUpdateCoalescer[1];
        self[0] = new LiveActivityUpdateCoalescer((task, d) -> scheduled.add(task), Duration.ofSeconds(10),
                key -> reopened.add(self[0].queue(key)));   // a write lands mid-flush
        self[0].queue("situation:a");
        scheduled.get(0).run();
        assertThat(reopened).containsExactly(true);
        assertThat(self[0].isPending("situation:a")).isTrue();
    }

    @Test
    void cancelDropsThePendingFlush() {
        coalescer.queue("situation:a");
        assertThat(coalescer.cancel("situation:a")).isTrue();
        scheduled.get(0).run();
        assertThat(flushed).isEmpty();
        assertThat(coalescer.cancel("situation:a")).isFalse();
    }

    @Test
    void aFailingFlushIsLogged_andTheKeyIsFreeAgain() {
        LiveActivityUpdateCoalescer c = new LiveActivityUpdateCoalescer((task, d) -> scheduled.add(task),
                Duration.ofSeconds(10), key -> { throw new IllegalStateException("db down"); });
        c.queue("situation:a");
        scheduled.get(0).run();   // does not throw
        assertThat(c.isPending("situation:a")).isFalse();
        assertThat(c.queue("situation:a")).isTrue();
    }

    @Test
    void aRejectedScheduleDoesNotLeaveTheKeyStuck() {
        LiveActivityUpdateCoalescer c = new LiveActivityUpdateCoalescer((task, d) -> {
            throw new java.util.concurrent.RejectedExecutionException("shut down");
        }, Duration.ofSeconds(10), flushed::add);
        assertThat(c.queue("situation:a")).isFalse();
        assertThat(c.isPending("situation:a")).isFalse();
    }
}
