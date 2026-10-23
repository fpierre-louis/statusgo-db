package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.practice.PracticeContent.RoutineSlot;
import io.sitprep.sitprepapi.practice.PracticeContent.RoutineSlotSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** EXEC-R1: the saved line reads as one sentence; the stored slot value stays as typed. */
class PracticeRoutinesSentenceCaseTest {

    private static final RoutineSlot WHO = new RoutineSlot("who", RoutineSlotSource.ADULT, null, null, List.of(), "a grown-up");
    private static final RoutineSlot PLACE = new RoutineSlot("place", RoutineSlotSource.PLACE, "INDOOR_SAFE_ROOM", null, List.of(), "our safe room");
    private static final RoutineSlot ROUTE = new RoutineSlot("route", RoutineSlotSource.TEXT, null, null, List.of(), "our backup route");

    @Test
    void aValueAtTheStartIsCapitalised() {
        assertThat(PracticeRoutines.sentenceCase("a grown-up", true, WHO)).isEqualTo("A grown-up");
        assertThat(PracticeRoutines.sentenceCase("Maya", true, WHO)).isEqualTo("Maya");
    }

    @Test
    void aPlaceOrContactMidSentenceLosesTheCapitalOnItsArticle() {
        assertThat(PracticeRoutines.sentenceCase("The basement", false, PLACE)).isEqualTo("the basement");
        assertThat(PracticeRoutines.sentenceCase("A neighbor's porch", false, PLACE)).isEqualTo("a neighbor's porch");
        assertThat(PracticeRoutines.sentenceCase("Theo's room", false, PLACE)).isEqualTo("Theo's room");
        assertThat(PracticeRoutines.sentenceCase("Rosa", false, PLACE)).isEqualTo("Rosa");
    }

    @Test
    void freeTextAndAdultNamesMidSentenceAreUntouched() {
        assertThat(PracticeRoutines.sentenceCase("The Old Mill Road", false, ROUTE)).isEqualTo("The Old Mill Road");
        assertThat(PracticeRoutines.sentenceCase("The coach", false, WHO)).isEqualTo("The coach");
    }
}
