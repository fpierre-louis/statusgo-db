package io.sitprep.sitprepapi.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-6 (V84): hours are validated at write and evaluated by the server,
 * DST-safely, in the listing's own zone. America/Denver is UTC-7 (MST) / UTC-6
 * (MDT); 2026 DST starts Sun 2026-03-08 02:00 and ends Sun 2026-11-01 02:00.
 */
class OpeningHoursTest {

    private static final String DENVER = "America/Denver";

    private static Map<String, Object> hours(String tz, Map<String, Object> weekly) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tz", tz);
        m.put("weekly", weekly);
        return m;
    }

    private static List<List<String>> r(String... bounds) {
        List<List<String>> out = new java.util.ArrayList<>();
        for (int i = 0; i < bounds.length; i += 2) out.add(List.of(bounds[i], bounds[i + 1]));
        return out;
    }

    private static OpeningHours.Status at(Map<String, Object> h, String instant) {
        return OpeningHours.parse(h).statusAt(Instant.parse(instant));
    }

    // ── validation ─────────────────────────────────────────────────────────

    @Test
    void acceptsTheContractShapeAndNormalisesIt() {
        Map<String, Object> h = hours(DENVER, Map.of("mon", r("09:00", "21:00"), "fri", r("18:00", "02:00")));
        h.put("note", "  Closes early on holidays  ");
        Map<String, Object> json = OpeningHours.parse(h).toJson();
        assertThat(json.get("tz")).isEqualTo(DENVER);
        assertThat(json.get("note")).isEqualTo("Closes early on holidays");
        assertThat(new java.util.ArrayList<Object>(((Map<?, ?>) json.get("weekly")).keySet())).containsExactly("mon", "fri");
    }

    @Test
    void refusesMalformedSchedules() {
        List<Object> bad = List.of(
                "9-5",
                hours("Mountain Time", Map.of()),                                   // not IANA
                hours("+05:00", Map.of()),                                          // offset, not a zone
                hours(DENVER, null),                                                // weekly required
                hours(DENVER, Map.of("monday", r("09:00", "17:00"))),               // day key
                hours(DENVER, Map.of("mon", r("9:00", "17:00"))),                   // HH:mm
                hours(DENVER, Map.of("mon", r("09:00", "25:00"))),
                hours(DENVER, Map.of("mon", r("24:00", "02:00"))),                  // 24:00 only as an end
                hours(DENVER, Map.of("mon", r("09:00", "09:00"))),                  // ambiguous
                hours(DENVER, Map.of("mon", r("06:00", "08:00", "09:00", "11:00", "12:00", "14:00", "15:00", "17:00"))),
                hours(DENVER, Map.of("mon", List.of(List.of("09:00")))),
                Map.of("tz", DENVER, "weekly", Map.of(), "open", true));            // unknown field
        for (Object b : bad) {
            assertThatThrownBy(() -> OpeningHours.parse(b)).as(String.valueOf(b))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        Map<String, Object> longNote = hours(DENVER, Map.of());
        longNote.put("note", "x".repeat(281));
        assertThatThrownBy(() -> OpeningHours.parse(longNote)).isInstanceOf(IllegalArgumentException.class);
    }

    // ── evaluation ─────────────────────────────────────────────────────────

    @Test
    void openWithTheNextClose_closedWithTheNextOpen() {
        Map<String, Object> h = hours(DENVER, Map.of("mon", r("09:00", "21:00")));
        // Mon 2026-09-28 12:00 MDT = 18:00Z
        OpeningHours.Status open = at(h, "2026-09-28T18:00:00Z");
        assertThat(open.openNow()).isTrue();
        assertThat(open.closesAt()).isEqualTo(Instant.parse("2026-09-29T03:00:00Z")); // 21:00 MDT
        assertThat(open.opensAt()).isNull();
        // Mon 22:00 MDT = Tue 04:00Z → next open is next Monday 09:00 MDT
        OpeningHours.Status closed = at(h, "2026-09-29T04:00:00Z");
        assertThat(closed.openNow()).isFalse();
        assertThat(closed.opensAt()).isEqualTo(Instant.parse("2026-10-05T15:00:00Z"));
        assertThat(closed.closesAt()).isNull();
    }

    @Test
    void aRangeCrossingMidnightStaysOpenIntoTheNextDay() {
        Map<String, Object> h = hours(DENVER, Map.of("fri", r("18:00", "02:00")));
        // Sat 2026-10-03 01:00 MDT = 07:00Z — open on FRIDAY's range
        OpeningHours.Status s = at(h, "2026-10-03T07:00:00Z");
        assertThat(s.openNow()).isTrue();
        assertThat(s.closesAt()).isEqualTo(Instant.parse("2026-10-03T08:00:00Z")); // Sat 02:00 MDT
        // Sat 03:00 MDT → closed until next Friday 18:00
        assertThat(at(h, "2026-10-03T09:00:00Z").opensAt()).isEqualTo(Instant.parse("2026-10-10T00:00:00Z"));
    }

    @Test
    void adjacentRangesAcrossMidnightMergeIntoOneClose() {
        Map<String, Object> h = hours(DENVER, Map.of("mon", r("20:00", "24:00"), "tue", r("00:00", "03:00")));
        // Mon 23:00 MDT = Tue 05:00Z: the close is Tue 03:00, not Mon 24:00
        assertThat(at(h, "2026-09-29T05:00:00Z").closesAt()).isEqualTo(Instant.parse("2026-09-29T09:00:00Z"));
    }

    @Test
    void aroundTheClockHasNoClose() {
        Map<String, Object> week = new LinkedHashMap<>();
        for (String d : OpeningHours.DAYS) week.put(d, r("00:00", "24:00"));
        OpeningHours.Status s = at(hours(DENVER, week), "2026-09-28T18:00:00Z");
        assertThat(s.openNow()).isTrue();
        assertThat(s.closesAt()).isNull();
    }

    @Test
    void springForward_rangeSpanningTheGapIsTwoRealHours() {
        Map<String, Object> h = hours(DENVER, Map.of("sun", r("01:00", "04:00")));
        // 01:30 MST = 08:30Z (before the jump)
        OpeningHours.Status before = at(h, "2026-03-08T08:30:00Z");
        assertThat(before.openNow()).isTrue();
        assertThat(before.closesAt()).isEqualTo(Instant.parse("2026-03-08T10:00:00Z")); // 04:00 MDT
        // 03:30 MDT = 09:30Z (after the jump) — still the same range
        assertThat(at(h, "2026-03-08T09:30:00Z").openNow()).isTrue();
    }

    @Test
    void springForward_aStartInsideTheSkippedHourOpensAtTheFirstRealInstant() {
        Map<String, Object> h = hours(DENVER, Map.of("sun", r("02:30", "05:00")));
        // 02:30 does not exist on 2026-03-08; it resolves to 03:30 MDT = 09:30Z.
        OpeningHours.Status s = at(h, "2026-03-08T09:00:00Z"); // 03:00 MDT
        assertThat(s.openNow()).isFalse();
        assertThat(s.opensAt()).isEqualTo(Instant.parse("2026-03-08T09:30:00Z"));
    }

    @Test
    void fallBack_theRepeatedHourCountsOnce() {
        Map<String, Object> h = hours(DENVER, Map.of("sun", r("00:00", "03:00")));
        // 00:00 MDT = 06:00Z; 03:00 MST = 10:00Z — four real hours.
        OpeningHours.Status s = at(h, "2026-11-01T09:30:00Z"); // 02:30 MST, second pass
        assertThat(s.openNow()).isTrue();
        assertThat(s.closesAt()).isEqualTo(Instant.parse("2026-11-01T10:00:00Z"));
    }

    @Test
    void aScheduleWithNoRangesMakesNoClaim() {
        Map<String, Object> noteOnly = hours(DENVER, Map.of());
        noteOnly.put("note", "By appointment");
        assertThat(at(noteOnly, "2026-09-28T18:00:00Z")).isEqualTo(OpeningHours.Status.UNKNOWN);
    }
}
