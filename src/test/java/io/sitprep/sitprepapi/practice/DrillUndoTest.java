package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.domain.DrillCompletion;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Who may undo a drill mark from the drill page, and until when (redesign FR-4). */
class DrillUndoTest {

    private static final Instant MARKED = Instant.parse("2026-10-22T18:00:00Z");
    private static final DrillCompletion MARK = new DrillCompletion(MARKED, null, "u-member");

    @Test
    void theRecorderHasTenMinutes() {
        assertThat(DrillUndo.undoUntil(MARK, "u-member", false, MARKED.plusSeconds(60)))
                .isEqualTo(MARKED.plus(Duration.ofMinutes(10)));
        assertThat(DrillUndo.undoUntil(MARK, "u-member", false, MARKED.plus(Duration.ofMinutes(10)))).isNull();
    }

    @Test
    void anotherMemberHasNoneButAnAdminHasTheSameWindow() {
        assertThat(DrillUndo.undoUntil(MARK, "u-other", false, MARKED.plusSeconds(60))).isNull();
        assertThat(DrillUndo.undoUntil(MARK, "u-other", true, MARKED.plusSeconds(60)))
                .isEqualTo(MARKED.plus(Duration.ofMinutes(10)));
        assertThat(DrillUndo.undoUntil(MARK, "u-other", true, MARKED.plus(Duration.ofMinutes(11)))).isNull();
    }

    @Test
    void aLegacyRowWithNoIdIsAdminOnly() {
        DrillCompletion legacy = new DrillCompletion(MARKED, "old@x.com", null);
        assertThat(DrillUndo.undoUntil(legacy, null, false, MARKED.plusSeconds(5))).isNull();
        assertThat(DrillUndo.isOwnRecentMark(legacy, "u-member", MARKED.plusSeconds(5))).isFalse();
        assertThat(DrillUndo.undoUntil(legacy, null, true, MARKED.plusSeconds(5))).isNotNull();
        assertThat(DrillUndo.undoUntil(null, "u-member", true, MARKED)).isNull();
    }
}
