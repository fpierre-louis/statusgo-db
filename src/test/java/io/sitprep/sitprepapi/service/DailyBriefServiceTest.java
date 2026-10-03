package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.BriefSlot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXEC-3C: one brief post, surfaced by an Eastern clock, worded by local time,
 * placed below officials with only newer posts above it.
 */
class DailyBriefServiceTest {

    @Test
    void surfacedAtIsTheLatestEasternBump() {
        // 13:30 EDT (17:30Z) → the 12:00 EDT bump (16:00Z).
        assertThat(DailyBriefService.surfacedAt(Instant.parse("2026-10-03T17:30:00Z")))
                .isEqualTo(Instant.parse("2026-10-03T16:00:00Z"));
        // 05:00 EDT → yesterday's 18:00 EDT.
        assertThat(DailyBriefService.surfacedAt(Instant.parse("2026-10-03T09:00:00Z")))
                .isEqualTo(Instant.parse("2026-10-02T22:00:00Z"));
        // After DST ends (Nov 2): 07:00 EST is 12:00Z.
        assertThat(DailyBriefService.surfacedAt(Instant.parse("2026-11-02T12:30:00Z")))
                .isEqualTo(Instant.parse("2026-11-02T12:00:00Z"));
    }

    @Test
    void theTipFollowsTheReadersLocalClock() {
        assertThat(DailyBriefService.slotFor(LocalTime.of(6, 30))).isEqualTo(BriefSlot.MORNING);
        assertThat(DailyBriefService.slotFor(LocalTime.of(13, 0))).isEqualTo(BriefSlot.MIDDAY);
        assertThat(DailyBriefService.slotFor(LocalTime.of(21, 0))).isEqualTo(BriefSlot.EVENING);
        assertThat(DailyBriefService.slotFor(LocalTime.of(2, 0))).isEqualTo(BriefSlot.EVENING);
    }

    /** A feed row reduced to what placement reads. */
    record Row(Instant createdAt, int tier, boolean pinned) {}

    @Test
    void itSitsBelowOfficialsAndThePinWithOnlyNewerPostsAbove() {
        Instant bump = Instant.parse("2026-10-03T16:00:00Z");
        List<Row> merged = new ArrayList<>(List.of(
                new Row(bump.minusSeconds(7200), 0, false),    // official
                new Row(bump.minusSeconds(3600), 4, true),     // the alert pin
                new Row(bump.minusSeconds(86_400), 4, false),  // older organic
                new Row(bump.plusSeconds(600), 4, false)));    // newer than the bump
        int at = DailyBriefService.insertIndex(merged, bump, Row::tier, Row::pinned, Row::createdAt);
        // Official + pin lead (2), then the one post newer than the bump.
        assertThat(at).isEqualTo(3);
    }
}
