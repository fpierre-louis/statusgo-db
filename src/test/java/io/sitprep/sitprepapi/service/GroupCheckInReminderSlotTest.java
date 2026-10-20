package io.sitprep.sitprepapi.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reminder timing (ask-to-check-in plan K1/K4): the early slots count from the
 * START, the "auto-ends in 12 hours" slot counts back from the END — so it
 * fires again after "Continue" pushes the end back.
 */
class GroupCheckInReminderSlotTest {

    private static final long H = 60;

    @Test
    void earlySlotsFollowTheStart() {
        assertThat(GroupCheckInReminderService.dueSlotAt(10, 47 * H)).isEqualTo(-1);
        assertThat(GroupCheckInReminderService.dueSlotAt(30, 47 * H)).isEqualTo(0);   // 30 min: members too
        assertThat(GroupCheckInReminderService.dueSlotAt(4 * H, 44 * H)).isEqualTo(1); // 4 h: members too
        assertThat(GroupCheckInReminderService.dueSlotAt(24 * H, 24 * H)).isEqualTo(3);
    }

    @Test
    void endingSoonFollowsTheEnd() {
        assertThat(GroupCheckInReminderService.dueSlotAt(36 * H, 12 * H))
                .isEqualTo(GroupCheckInReminderService.ENDING_SOON_SLOT);
    }

    @Test
    void afterContinueEndingSoonWaitsForTheNewEnd() {
        // 40 h in, continued: the end is 48 h away again. Only the elapsed
        // slots are due — all already fired — so nothing fires until 12 h
        // before the new end.
        assertThat(GroupCheckInReminderService.dueSlotAt(40 * H, 48 * H)).isEqualTo(3);
        assertThat(GroupCheckInReminderService.dueSlotAt(76 * H, 12 * H))
                .isEqualTo(GroupCheckInReminderService.ENDING_SOON_SLOT);
    }

    @Test
    void onlyTheEndingSoonReminderAsksAboutContinuing() {
        for (int slot = 0; slot < GroupCheckInReminderService.ENDING_SOON_SLOT; slot++) {
            assertThat(GroupCheckInReminderService.slotBody(slot)).as("slot " + slot)
                    .doesNotContainIgnoringCase("continue").doesNotContainIgnoringCase("keep going");
        }
        assertThat(GroupCheckInReminderService.slotBody(2))
                .isEqualTo("12 hours in. See who's answered, and end it once everyone's accounted for.");
        assertThat(GroupCheckInReminderService.slotBody(3))
                .isEqualTo("Day 1 is done. See where everyone stands, and end it once everyone's accounted for.");
        assertThat(GroupCheckInReminderService.slotBody(GroupCheckInReminderService.ENDING_SOON_SLOT))
                .isEqualTo("Auto-ends in 12 hours. Continue or close it out?");
    }
}
