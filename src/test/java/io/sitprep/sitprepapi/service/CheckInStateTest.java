package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.dto.GroupMemberViewDto.CheckIn;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The roster row's ask-and-answer rule (household drawer gameplan §3.2, owner
 * rulings Q3 + Q3b (i)). One row per case; times are hours relative to NOW.
 */
class CheckInStateTest {

    private static final Instant NOW = Instant.parse("2026-10-08T18:00:00Z");

    private static Instant h(double hoursAgo) {
        return NOW.minus(Duration.ofMinutes(Math.round(hoursAgo * 60)));
    }

    /** A case: inputs, then the expected state / value / showUntil. */
    record Case(String name,
                Instant askedAt, String value, Instant updatedAt,
                boolean alert, Instant activatedAt, Instant expiresAt,
                String state, String expectValue, Instant showUntil) {
        @Override public String toString() { return name; }
    }

    private static Case calm(String name, Instant askedAt, String value, Instant updatedAt,
                             String state, String expectValue, Instant showUntil) {
        return new Case(name, askedAt, value, updatedAt, false, null, null, state, expectValue, showUntil);
    }

    static Stream<Arguments> cases() {
        Instant activated = h(5);
        Instant expires = activated.plus(Duration.ofHours(48));
        return Stream.of(
                calm("never asked, no status: NONE",
                        null, "NO RESPONSE", null, "NONE", null, null),
                calm("never asked, old SAFE: NONE",
                        null, "SAFE", h(30), "NONE", null, null),
                calm("asked 2h ago, last status 2d ago: AWAITING until ask + 24h",
                        h(2), "SAFE", h(48), "AWAITING", null, h(2).plus(Duration.ofHours(24))),
                calm("asked, answered SAFE after: ANSWERED until answer + 24h",
                        h(3), "SAFE", h(1), "ANSWERED", "SAFE", h(1).plus(Duration.ofHours(24))),
                calm("self-report under 24h, no ask: ANSWERED (Q3)",
                        null, "SAFE", h(5), "ANSWERED", "SAFE", h(5).plus(Duration.ofHours(24))),
                // The ask itself has left the 24h read window by now, so it
                // arrives as null — the answer still shows a full day.
                calm("reply at hour 23 shows until answeredAt + 24h",
                        null, "SAFE", h(7), "ANSWERED", "SAFE", h(7).plus(Duration.ofHours(24))),
                calm("SAFE lapses after 24h: NONE",
                        null, "SAFE", h(24.5), "NONE", null, null),
                calm("HELP persists: ANSWERED, never lapses",
                        null, "HELP", h(200), "ANSWERED", "HELP", null),
                calm("INJURED persists: ANSWERED, never lapses",
                        null, "injured", h(90), "ANSWERED", "INJURED", null),
                calm("INJURED then a new ask stays INJURED",
                        h(1), "INJURED", h(10), "ANSWERED", "INJURED", null),
                calm("HELP then a new ask stays HELP",
                        h(1), "HELP", h(10), "ANSWERED", "HELP", null),
                calm("fresh SAFE then a new ask: AWAITING (Q3b i)",
                        h(1), "SAFE", h(3), "AWAITING", null, h(1).plus(Duration.ofHours(24))),
                new Case("check-in running, SAFE since it started: ANSWERED until it ends",
                        h(5), "SAFE", h(2), true, activated, expires, "ANSWERED", "SAFE", expires),
                new Case("check-in running, SAFE from before it started: AWAITING until it ends",
                        h(5), "SAFE", h(20), true, activated, expires, "AWAITING", null, expires),
                new Case("check-in running, answered then nudged: still ANSWERED (window rule)",
                        h(0.5), "SAFE", h(2), true, activated, expires, "ANSWERED", "SAFE", expires),
                new Case("check-in running, INJURED from before it: bad news never lapses",
                        h(5), "INJURED", h(40), true, activated, expires, "ANSWERED", "INJURED", null),
                new Case("check-in running, never asked, no fresh status: NONE",
                        null, "SAFE", h(20), true, activated, expires, "NONE", null, null)
        ).map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void theRule(Case c) {
        CheckIn out = CheckInState.of(c.askedAt(), c.value(), c.updatedAt(), "Dione",
                c.alert(), c.activatedAt(), c.expiresAt(), NOW);

        assertThat(out.state()).as("state").isEqualTo(c.state());
        assertThat(out.value()).as("value").isEqualTo(c.expectValue());
        assertThat(out.showUntil()).as("showUntil").isEqualTo(c.showUntil());
        if ("ANSWERED".equals(c.state())) {
            assertThat(out.answeredAt()).as("answeredAt").isEqualTo(c.updatedAt());
            assertThat(out.setByName()).as("setByName rides an answer").isEqualTo("Dione");
        } else {
            assertThat(out.answeredAt()).as("no answer to show").isNull();
            assertThat(out.setByName()).isNull();
        }
    }
}
