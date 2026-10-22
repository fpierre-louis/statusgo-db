package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.practice.PracticeCheckInService.Confidence;
import org.junit.jupiter.api.Test;

import static io.sitprep.sitprepapi.practice.PracticeCheckInService.nextInterval;
import static org.assertj.core.api.Assertions.assertThat;

/** Every row of the CHECKIN_COMPONENTS scheduling table, plus the ladder's edges. */
class PracticeCheckInSchedulingTest {

    @Test
    void correctWithHadItOrNoConfidenceClimbsTheLadder() {
        assertThat(nextInterval(2, true, null)).isEqualTo(9);
        assertThat(nextInterval(9, true, null)).isEqualTo(30);
        assertThat(nextInterval(30, true, null)).isEqualTo(90);
        assertThat(nextInterval(90, true, null)).as("the top rung holds").isEqualTo(90);
        assertThat(nextInterval(2, true, Confidence.HAD_IT)).isEqualTo(9);
        assertThat(nextInterval(30, true, Confidence.HAD_IT)).isEqualTo(90);
    }

    @Test
    void correctButPartlyRepeatsTheCurrentInterval() {
        for (int d : new int[] {2, 9, 30, 90}) assertThat(nextInterval(d, true, Confidence.PARTLY)).isEqualTo(d);
    }

    @Test
    void correctButNotYetHalvesTheIntervalNeverUnderTwo() {
        assertThat(nextInterval(90, true, Confidence.NOT_YET)).isEqualTo(45);
        assertThat(nextInterval(30, true, Confidence.NOT_YET)).isEqualTo(15);
        assertThat(nextInterval(9, true, Confidence.NOT_YET)).isEqualTo(4);
        assertThat(nextInterval(2, true, Confidence.NOT_YET)).isEqualTo(2);
        assertThat(nextInterval(3, true, Confidence.NOT_YET)).isEqualTo(2);
    }

    @Test
    void incorrectIsTwoDaysWhateverTheConfidence() {
        for (int d : new int[] {2, 9, 30, 90}) {
            assertThat(nextInterval(d, false, null)).isEqualTo(2);
            for (Confidence c : Confidence.values()) assertThat(nextInterval(d, false, c)).isEqualTo(2);
        }
    }

    @Test
    void aHalvedIntervalClimbsToTheNextRungAboveIt() {
        assertThat(nextInterval(4, true, null)).isEqualTo(9);
        assertThat(nextInterval(15, true, null)).isEqualTo(30);
        assertThat(nextInterval(45, true, null)).isEqualTo(90);
    }
}
