package io.sitprep.sitprepapi.util;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A resource listing's weekly opening hours (V84, map-ideal BE-6) — validated
 * once at write, and evaluated by the server on every read so the client never
 * guesses "open".
 *
 * <pre>
 * { "tz": "America/Denver",
 *   "weekly": { "mon": [["09:00","21:00"]], "fri": [["18:00","02:00"]] },
 *   "note": "Closes early on holidays" }
 * </pre>
 *
 * <ul>
 *   <li>{@code tz} — required, an IANA zone id ({@code ZoneId.getAvailableZoneIds()}).</li>
 *   <li>{@code weekly} — required object; keys {@code mon…sun}; each 0–3 ranges of
 *       {@code ["HH:mm","HH:mm"]}. A missing day is closed. {@code "24:00"} is
 *       allowed as an end ("until midnight"). An end earlier than its start
 *       crosses midnight into the next day. Equal start and end is refused —
 *       it could mean zero hours or twenty-four.</li>
 *   <li>{@code note} — optional free text, ≤ 280 chars.</li>
 * </ul>
 *
 * <p>Evaluation is DST-safe: every boundary is built as a
 * {@link ZonedDateTime} in the listing's zone, so a spring-forward day's
 * 09:00–17:00 is eight real hours and a range starting inside the skipped hour
 * starts at the first instant that exists. A week with no ranges at all
 * (a note-only record, "by appointment") makes NO open/closed claim.</p>
 */
public final class OpeningHours {

    public static final List<String> DAYS = List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");
    private static final Set<String> KEYS = Set.of("tz", "weekly", "note");
    private static final int MAX_RANGES_PER_DAY = 3;
    private static final int MAX_NOTE = 280;
    private static final Pattern HH_MM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    /** Look this far ahead for the next boundary. A weekly schedule repeats inside it. */
    private static final int HORIZON_DAYS = 8;

    /** One range, in minutes from local midnight. {@code end} may be 1440, or ≤ start (crosses midnight). */
    private record Range(int start, int end) {}

    private final ZoneId zone;
    private final Map<DayOfWeek, List<Range>> weekly;
    private final Map<String, Object> canonical;

    private OpeningHours(ZoneId zone, Map<DayOfWeek, List<Range>> weekly, Map<String, Object> canonical) {
        this.zone = zone;
        this.weekly = weekly;
        this.canonical = canonical;
    }

    /** What the server says about "now", computed from the schedule. All null when there is nothing to say. */
    public record Status(Boolean openNow, Instant closesAt, Instant opensAt) {
        public static final Status UNKNOWN = new Status(null, null, null);
    }

    /**
     * Validate and normalise a client-supplied schedule.
     *
     * @throws IllegalArgumentException with a human-readable reason (→ 400)
     */
    @SuppressWarnings("unchecked")
    public static OpeningHours parse(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) throw bad("hours must be an object");
        for (Object k : map.keySet()) {
            if (!(k instanceof String key) || !KEYS.contains(key)) {
                throw bad("hours has an unknown field: " + k);
            }
        }
        Object tzRaw = map.get("tz");
        if (!(tzRaw instanceof String tz) || !ZoneId.getAvailableZoneIds().contains(tz)) {
            throw bad("hours.tz must be an IANA time zone, e.g. America/Denver");
        }
        Object weeklyRaw = map.get("weekly");
        if (!(weeklyRaw instanceof Map<?, ?> week)) throw bad("hours.weekly must be an object");

        Map<DayOfWeek, List<Range>> weekly = new EnumMap<>(DayOfWeek.class);
        Map<String, Object> weeklyOut = new LinkedHashMap<>();
        for (Object k : week.keySet()) {
            if (!(k instanceof String day) || !DAYS.contains(day)) {
                throw bad("hours.weekly keys are mon, tue, wed, thu, fri, sat, sun");
            }
        }
        for (String day : DAYS) {
            if (!week.containsKey(day)) continue;
            Object rangesRaw = week.get(day);
            if (!(rangesRaw instanceof List<?> ranges)) throw bad("hours.weekly." + day + " must be a list");
            if (ranges.size() > MAX_RANGES_PER_DAY) {
                throw bad("hours.weekly." + day + " has more than " + MAX_RANGES_PER_DAY + " ranges");
            }
            List<Range> parsed = new ArrayList<>();
            List<List<String>> out = new ArrayList<>();
            for (Object r : ranges) {
                if (!(r instanceof List<?> pair) || pair.size() != 2
                        || !(pair.get(0) instanceof String s) || !(pair.get(1) instanceof String e)) {
                    throw bad("each range in hours.weekly." + day + " must be [\"HH:mm\",\"HH:mm\"]");
                }
                if (!HH_MM.matcher(s).matches()) throw bad("bad start time " + s + " on " + day);
                if (!"24:00".equals(e) && !HH_MM.matcher(e).matches()) throw bad("bad end time " + e + " on " + day);
                int start = minutes(s);
                int end = minutes(e);
                if (start == end) {
                    throw bad("a range on " + day + " starts and ends at " + s + " — use [\"00:00\",\"24:00\"] for all day");
                }
                parsed.add(new Range(start, end));
                out.add(List.of(s, e));
            }
            weekly.put(dayOf(day), parsed);
            weeklyOut.put(day, out);
        }

        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("tz", tzRaw);
        canonical.put("weekly", weeklyOut);
        Object noteRaw = map.get("note");
        if (noteRaw != null) {
            if (!(noteRaw instanceof String note)) throw bad("hours.note must be text");
            String n = note.trim();
            if (n.length() > MAX_NOTE) throw bad("hours.note is longer than " + MAX_NOTE + " characters");
            if (!n.isEmpty()) canonical.put("note", n);
        }
        return new OpeningHours(ZoneId.of((String) tzRaw), weekly, canonical);
    }

    /** The normalised schedule, as stored and as served. */
    public Map<String, Object> toJson() {
        return canonical;
    }

    /**
     * Open or closed at {@code now}, and the next boundary.
     *
     * <ul>
     *   <li>open → {@code closesAt} (null only when no close falls within the
     *       next week, i.e. open around the clock);</li>
     *   <li>closed → {@code opensAt} (null when nothing opens within a week);</li>
     *   <li>no ranges on any day → {@link Status#UNKNOWN}.</li>
     * </ul>
     */
    public Status statusAt(Instant now) {
        List<Instant[]> intervals = intervalsAround(now);
        if (intervals.isEmpty()) return Status.UNKNOWN;
        Instant horizon = now.plus(Duration.ofDays(7));
        for (Instant[] iv : intervals) {
            if (!iv[0].isAfter(now) && now.isBefore(iv[1])) {
                return new Status(true, iv[1].isAfter(horizon) ? null : iv[1], null);
            }
        }
        for (Instant[] iv : intervals) {
            if (iv[0].isAfter(now)) {
                return new Status(false, null, iv[0].isAfter(horizon.plus(Duration.ofDays(1))) ? null : iv[0]);
            }
        }
        return new Status(false, null, null);
    }

    /** Concrete, merged intervals from yesterday (a range may cross into today) through the horizon. */
    private List<Instant[]> intervalsAround(Instant now) {
        LocalDate today = now.atZone(zone).toLocalDate();
        List<Instant[]> raw = new ArrayList<>();
        for (int d = -1; d <= HORIZON_DAYS; d++) {
            LocalDate date = today.plusDays(d);
            List<Range> ranges = weekly.get(date.getDayOfWeek());
            if (ranges == null) continue;
            for (Range r : ranges) {
                Instant start = at(date, r.start());
                Instant end = r.end() == 1440
                        ? at(date.plusDays(1), 0)
                        : r.end() < r.start() ? at(date.plusDays(1), r.end()) : at(date, r.end());
                if (end.isAfter(start)) raw.add(new Instant[] { start, end });
            }
        }
        raw.sort(Comparator.comparing(iv -> iv[0]));
        List<Instant[]> merged = new ArrayList<>();
        for (Instant[] iv : raw) {
            Instant[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && !iv[0].isAfter(last[1])) {
                if (iv[1].isAfter(last[1])) last[1] = iv[1];
            } else {
                merged.add(new Instant[] { iv[0], iv[1] });
            }
        }
        return merged;
    }

    /** Local date + minutes → instant; a time inside a DST gap resolves to the first instant that exists. */
    private Instant at(LocalDate date, int minutes) {
        return ZonedDateTime.of(date, LocalTime.of(minutes / 60, minutes % 60), zone).toInstant();
    }

    private static int minutes(String hhmm) {
        if ("24:00".equals(hhmm)) return 1440;
        return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3, 5));
    }

    private static DayOfWeek dayOf(String day) {
        return DayOfWeek.of(DAYS.indexOf(day) + 1);
    }

    private static IllegalArgumentException bad(String why) {
        return new IllegalArgumentException(why);
    }
}
