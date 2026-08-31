package io.sitprep.sitprepapi.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The location-sharing gate.
 *
 * <p>These pin the two things that actually went wrong, neither of which is a
 * logic error: the rule was written down in FOUR places (this gate, a
 * character-identical copy in {@code UserInfoService}, the frontend's
 * {@code MapVisibilityPage.defaultFor}, and a javadoc paragraph on
 * {@code MeDto.ProfileDto}), and two of the four drifted. So the tests worth
 * having are the ones that state the DEFAULTS and the absoluteness of
 * {@code never} — the two facts a copy is most likely to get wrong.</p>
 */
class LocationSharingTest {

    private static final String GROUP = "g-hoa";
    private static final String HOUSEHOLD_TYPE = "Household";
    private static final String CIRCLE_TYPE = "HOA/Neighborhood";

    private static Map<String, String> prefs(String groupId, String mode) {
        Map<String, String> m = new HashMap<>();
        m.put(groupId, mode);
        return m;
    }

    @Nested
    @DisplayName("an unset entry")
    class Defaults {

        @Test
        @DisplayName("is check-in-only for a household")
        void householdDefault() {
            assertThat(LocationSharing.defaultFor(HOUSEHOLD_TYPE))
                    .isEqualTo(LocationSharing.CHECK_IN_ONLY);
        }

        @Test
        @DisplayName("is never for anything else — and this is the one the FE got wrong")
        void circleDefault() {
            // The frontend said "check-in-only" here, which reads to a user as
            // "my circle sees me during an emergency". The server reveals
            // nothing. That is the defect this class exists to make unrepeatable.
            assertThat(LocationSharing.defaultFor(CIRCLE_TYPE))
                    .isEqualTo(LocationSharing.NEVER);
            assertThat(LocationSharing.defaultFor(null))
                    .isEqualTo(LocationSharing.NEVER);
        }

        @Test
        @DisplayName("resolves through effectiveMode for a null, absent or blank value")
        void resolvesToDefault() {
            assertThat(LocationSharing.effectiveMode(null, GROUP, HOUSEHOLD_TYPE))
                    .isEqualTo(LocationSharing.CHECK_IN_ONLY);
            assertThat(LocationSharing.effectiveMode(Map.of(), GROUP, HOUSEHOLD_TYPE))
                    .isEqualTo(LocationSharing.CHECK_IN_ONLY);
            assertThat(LocationSharing.effectiveMode(prefs(GROUP, "  "), GROUP, CIRCLE_TYPE))
                    .isEqualTo(LocationSharing.NEVER);
        }

        @Test
        @DisplayName("does not shadow an explicit choice")
        void explicitWins() {
            assertThat(LocationSharing.effectiveMode(prefs(GROUP, LocationSharing.ALWAYS), GROUP, CIRCLE_TYPE))
                    .isEqualTo(LocationSharing.ALWAYS);
        }
    }

    @Nested
    @DisplayName("never is absolute")
    class NeverIsAbsolute {

        @Test
        @DisplayName("stays hidden even during an active alert")
        void neverSurvivesAnAlert() {
            // Locked 2026-07-02. This protects users who cannot risk their
            // location reaching a group under ANY circumstance — e.g.
            // domestic-violence survivors. An alert-time override would be a
            // safety regression, not a feature.
            assertThat(LocationSharing.visible(LocationSharing.NEVER, true)).isFalse();
            assertThat(LocationSharing.shouldShare(
                    prefs(GROUP, LocationSharing.NEVER), GROUP, HOUSEHOLD_TYPE, true)).isFalse();
        }
    }

    @Nested
    @DisplayName("check-in-only")
    class CheckInOnly {

        @Test
        @DisplayName("reveals only while the alert is active")
        void gatedOnAlert() {
            assertThat(LocationSharing.visible(LocationSharing.CHECK_IN_ONLY, false)).isFalse();
            assertThat(LocationSharing.visible(LocationSharing.CHECK_IN_ONLY, true)).isTrue();
        }

        @Test
        @DisplayName("is why a default household map is empty at rest")
        void defaultHouseholdIsHiddenAtRest() {
            // The state every never-configured household is in, and the reason
            // the map copy may not say anyone opted out: nobody chose anything.
            assertThat(LocationSharing.shouldShare(Map.of(), GROUP, HOUSEHOLD_TYPE, false)).isFalse();
            assertThat(LocationSharing.shouldShare(Map.of(), GROUP, HOUSEHOLD_TYPE, true)).isTrue();
        }

        @Test
        @DisplayName("and why a default circle is empty even during one")
        void defaultCircleIsHiddenDuringAnAlert() {
            assertThat(LocationSharing.shouldShare(Map.of(), GROUP, CIRCLE_TYPE, true)).isFalse();
        }
    }

    @Test
    @DisplayName("always reveals regardless of alert state")
    void alwaysReveals() {
        assertThat(LocationSharing.visible(LocationSharing.ALWAYS, false)).isTrue();
        assertThat(LocationSharing.visible(LocationSharing.ALWAYS, true)).isTrue();
    }

    @Test
    @DisplayName("an unrecognised mode fails closed")
    void unknownFailsClosed() {
        // A typo, a future mode a rolled-back client still sends, a hand-edited
        // row. The permissive reading of an unknown value is the one that leaks.
        assertThat(LocationSharing.visible("sometimes", true)).isFalse();
        assertThat(LocationSharing.visible(null, true)).isFalse();
    }

    @Test
    @DisplayName("group-type matching is case-insensitive")
    void caseInsensitive() {
        assertThat(LocationSharing.defaultFor("household")).isEqualTo(LocationSharing.CHECK_IN_ONLY);
        assertThat(LocationSharing.defaultFor("HOUSEHOLD")).isEqualTo(LocationSharing.CHECK_IN_ONLY);
    }
}
