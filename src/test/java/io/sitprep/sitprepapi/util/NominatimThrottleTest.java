package io.sitprep.sitprepapi.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nominatim's policy is one request per second for the whole app. A burst is
 * spaced out; a queue longer than the wait budget is refused, not held.
 */
class NominatimThrottleTest {

    @BeforeEach
    @AfterEach
    void reset() {
        NominatimThrottle.reset();
    }

    @Test
    void aBurstIsSpacedASecondApartThenRefused() {
        List<Long> waits = new ArrayList<>();
        long now = 1_000_000L;
        assertThat(NominatimThrottle.acquire(() -> now, waits::add)).isTrue();   // slot now
        assertThat(NominatimThrottle.acquire(() -> now, waits::add)).isTrue();   // +1.1 s
        assertThat(NominatimThrottle.acquire(() -> now, waits::add)).isTrue();   // +2.2 s
        assertThat(NominatimThrottle.acquire(() -> now, waits::add)).isFalse();  // +3.3 s > 3 s budget
        assertThat(waits).containsExactly(1_100L, 2_200L);
    }

    @Test
    void spacedCallsNeverWait() {
        List<Long> waits = new ArrayList<>();
        assertThat(NominatimThrottle.acquire(() -> 0L, waits::add)).isTrue();
        assertThat(NominatimThrottle.acquire(() -> 2_000L, waits::add)).isTrue();
        assertThat(waits).isEmpty();
    }
}
