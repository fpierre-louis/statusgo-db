package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.service.PushPolicyService.Category;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mention notification must route to a Category. Regression lock for the
 * 2026-08-22 defect where {@code GroupPostService} emitted a type string that
 * no reader accepted.
 *
 * <p><b>Why a test and not a review note.</b> The failure was invisible at
 * every layer that could have caught it. An unmapped type is not an error —
 * {@link NotificationService#mapTypeToCategory} returns null by design, for
 * back-compat with legacy call sites, and the delivery path reads that null as
 * "no policy applies" and proceeds to send. So the mistyped string produced a
 * SUCCESSFUL push with the wrong lane, the wrong APNs category and the wrong FE
 * treatment, and logged nothing. Nothing in the build, the test suite or the
 * runtime said a word.</p>
 *
 * <p>That is the same shape as {@code extreme_heat} vs {@code heat}: one writer
 * emits a string no reader accepts, and the silent default takes the wrong
 * branch. The generalisation worth keeping is that <b>a vocabulary whose
 * mismatch is silent needs a compile-time or test-time gate</b>, because there
 * is no runtime moment at which anyone finds out.</p>
 */
class NotificationMentionRoutingTest {

    @Test
    @DisplayName("the mention type the emitter sends maps to Category.MENTION")
    void mentionTypeMapsToMentionCategory() {
        // Reads the same constant GroupPostService passes to the delivery
        // call, so this fails if either side is retyped independently.
        assertThat(NotificationService.mapTypeToCategory(NotificationService.TYPE_POST_MENTION))
                .isEqualTo(Category.MENTION);
    }

    @Test
    @DisplayName("an unmapped type still yields null — the branch this defect rode in on")
    void unmappedTypeYieldsNull() {
        // Documents the hazard rather than asserting it is fine. Null here is
        // deliberate back-compat, and it is exactly why a typo is silent: the
        // caller treats null as "skip policy", not as "something is wrong".
        assertThat(NotificationService.mapTypeToCategory("mention_notification")).isNull();
        assertThat(NotificationService.mapTypeToCategory("not_a_real_type")).isNull();
    }

    @Test
    @DisplayName("MENTION is declared Lane B — inbox row, no interruptive push")
    void mentionIsLaneB() {
        // The policy doc's ruling, locked here so a future lane edit is a
        // deliberate act. A mention is not an emergency: it earns an inbox row
        // and no number.
        assertThat(PushPolicyService.defaultLaneFor(Category.MENTION))
                .isEqualTo(PushPolicyService.Lane.B);
    }

    @Test
    @DisplayName("weekly drill notification types map to the drill category")
    void weeklyDrillTypesMapToDrillCategory() {
        assertThat(NotificationService.mapTypeToCategory(HouseholdChallengeScheduler.TYPE_KICKOFF))
                .isEqualTo(Category.WEEKLY_DRILL_REMINDER);
        assertThat(NotificationService.mapTypeToCategory(HouseholdChallengeScheduler.TYPE_NUDGE))
                .isEqualTo(Category.WEEKLY_DRILL_REMINDER);
    }

    @Test
    @DisplayName("weekly drill reminders are declared Lane B")
    void weeklyDrillReminderIsLaneB() {
        assertThat(PushPolicyService.defaultLaneFor(Category.WEEKLY_DRILL_REMINDER))
                .isEqualTo(PushPolicyService.Lane.B);
    }
}
