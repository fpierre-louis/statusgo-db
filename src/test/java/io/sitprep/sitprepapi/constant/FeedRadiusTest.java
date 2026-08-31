package io.sitprep.sitprepapi.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The feed-radius ceiling. One value, three former homes.
 */
class FeedRadiusTest {

    @Test
    @DisplayName("the miles figure matches what the frontend used to derive by hand")
    void milesMatchesTheRetiredFrontendConstant() {
        // radiusOverride.js carried `MAX_RADIUS_MI = 310` with `500 / 1.609344`
        // worked out in a comment. Pinning the same answer here is what makes
        // deleting that constant a safe swap rather than a guess.
        assertThat(FeedRadius.maxMiles()).isEqualTo(310);
    }

    @Test
    @DisplayName("miles are FLOORED, never rounded up")
    void flooredNotRounded() {
        // 500 km is 310.7 miles. This number is spoken to the user as the reach
        // of the widest rung, so rounding up would claim distance the server
        // does not serve. Understating by two thirds of a mile is the safe
        // direction for a coverage claim.
        assertThat(FeedRadius.MAX_KM / 1.609344).isGreaterThan(FeedRadius.maxMiles());
    }

    @Test
    @DisplayName("the clamp itself is unchanged at 500 km")
    void clampUnchanged() {
        // Both community query paths clamped at this before it was extracted;
        // the extraction must not have moved it.
        assertThat(FeedRadius.MAX_KM).isEqualTo(500.0);
    }
}
