package io.sitprep.sitprepapi.constant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closes audit finding <b>F-5</b>: the push path bounded location age and the
 * pull path structurally could not.
 *
 * <p>{@code GET /api/alerts/feed} took bare {@code lat}/{@code lng}, so the
 * frontend sent a coordinate with no age and the server had nothing to apply a
 * rule to. Past the push window the two failed in opposite directions and the
 * union was silence: push correctly went quiet, pull kept rendering the stale
 * coordinate as current, and the user read an authoritative "0 active near you"
 * with no notification to contradict it.</p>
 *
 * <p>These tests pin that there is now exactly ONE window, that both paths read
 * it, and that "we were not told" stays distinguishable from "we checked and it
 * is fine".</p>
 */
class LocationFreshnessTest {

    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");

    @AfterEach
    void restoreDefault() {
        LocationFreshness.resetForTest();
    }

    @Test
    void thePushPathAndTheFeedReadTheSameWindow() {
        // The point of the whole exercise. AlertDispatchService used to hold its
        // own @Value; a second one on the feed side would have made this the
        // fourth constant in this project to exist in two places, after the
        // three radius clamps and the four status palettes.
        Instant cutoff = LocationFreshness.cutoff(NOW);
        assertThat(cutoff).isEqualTo(NOW.minus(Duration.ofDays(LocationFreshness.maxAgeDays())));
        assertThat(LocationFreshness.maxAgeDays())
                .isEqualTo(LocationFreshness.DEFAULT_MAX_AGE_DAYS);
    }

    @Test
    void aFixInsideTheWindowIsCurrent() {
        Instant yesterday = NOW.minus(Duration.ofDays(1));
        assertThat(LocationFreshness.isStale(yesterday, NOW)).isFalse();
    }

    @Test
    void aFixPastTheWindowIsStale() {
        // The measured case: a coordinate captured in Lehi UT while the user is
        // standing in Beaumont TX under a Severe / Immediate Flood Warning.
        Instant threeWeeks = NOW.minus(Duration.ofDays(21));
        assertThat(LocationFreshness.isStale(threeWeeks, NOW)).isTrue();
    }

    @Test
    void anUnknownFixIsNotQuietlyTreatedAsFresh() {
        // A client that omits the timestamp has asserted nothing. `isStale`
        // returns false because there is no evidence of staleness — but the
        // caller must render that as UNKNOWN, which is what the null LocationAge
        // below is for. Passing it off as "checked and fine" would reopen F-5
        // through the front door.
        assertThat(LocationFreshness.isStale(null, NOW)).isFalse();
    }

    @Test
    void aRetunedWindowMovesBothPathsAtOnce() {
        // Proves the consolidation is real rather than cosmetic: change the one
        // value and the verdict changes with it, for whoever reads it.
        LocationFreshness.bindForTest(1);
        Instant twoDaysAgo = Instant.now().minus(Duration.ofDays(2));

        assertThat(LocationFreshness.isStale(twoDaysAgo, Instant.now())).isTrue();
        assertThat(LocationFreshness.maxAgeDays()).isEqualTo(1);
        assertThat(LocationFreshness.cutoff(NOW)).isEqualTo(NOW.minus(Duration.ofDays(1)));
    }
}
