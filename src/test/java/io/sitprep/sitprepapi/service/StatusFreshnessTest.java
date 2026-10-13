package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.dto.MemberStatusFrame;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StatusFreshnessTest {

    private static final Instant AT = Instant.parse("2026-10-13T12:00:00Z");

    @Test
    void safeIsCurrentForTheSameWindowAsTheRosterRow() {
        assertThat(StatusFreshness.freshUntil("SAFE", AT))
                .isEqualTo(AT.plus(CheckInRequestService.OUTSIDE_CHECK_IN_WINDOW))
                .isEqualTo(AT.plus(Duration.ofHours(24)));
        assertThat(StatusFreshness.freshUntil(" safe ", AT)).isEqualTo(AT.plus(Duration.ofHours(24)));
    }

    @Test
    void badNewsNeverLapses() {
        assertThat(StatusFreshness.freshUntil("HELP", AT)).isNull();
        assertThat(StatusFreshness.freshUntil("INJURED", AT.minus(Duration.ofDays(400)))).isNull();
    }

    @Test
    void noStatusIsNeverCurrent() {
        assertThat(StatusFreshness.freshUntil(null, AT)).isEqualTo(Instant.EPOCH);
        assertThat(StatusFreshness.freshUntil("NO RESPONSE", AT)).isEqualTo(Instant.EPOCH);
        // A SAFE with no time is not evidence of anything current.
        assertThat(StatusFreshness.freshUntil("SAFE", null)).isEqualTo(Instant.EPOCH);
    }

    @Test
    void theLiveFrameCarriesTheSameAnswer() {
        MemberStatusFrame f = new MemberStatusFrame("a@b.c", "SAFE", "#fff", AT, null);
        assertThat(f.freshUntil()).isEqualTo(AT.plus(Duration.ofHours(24)));
        assertThat(new MemberStatusFrame("a@b.c", "INJURED", null, AT, "Dione").freshUntil()).isNull();
    }
}
