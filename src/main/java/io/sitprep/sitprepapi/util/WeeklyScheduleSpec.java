package io.sitprep.sitprepapi.util;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * §4 Round 3 — parsed form of a household ritual schedule string.
 *
 * <p>Wire format: {@code WEEKLY_<DAY>_<HH:MM>} where {@code <DAY>} is
 * one of {@code MON / TUE / WED / THU / FRI / SAT / SUN} and
 * {@code <HH:MM>} is 24-hour local time (e.g. {@code 19:00},
 * {@code 06:30}, {@code 22:15}). Round 1's hardcoded
 * {@code WEEKLY_SUN_19:00} is the canonical example and still the
 * default — extending the parser to other day/hour combinations is
 * what unlocks the FE picker UI.</p>
 *
 * <p>Round 4 can add additional cadences (e.g. {@code MONTHLY_*})
 * by adding parallel parse paths here; the scheduler treats an
 * empty {@code parse()} as "refuse to fire", so unknown specs are
 * silently inert rather than crashing.</p>
 */
public record WeeklyScheduleSpec(DayOfWeek day, LocalTime time) {

    private static final String PREFIX = "WEEKLY_";

    public static Optional<WeeklyScheduleSpec> parse(String raw) {
        if (raw == null || !raw.startsWith(PREFIX)) return Optional.empty();
        // Strip prefix → "SUN_19:00" → split on first underscore.
        String rest = raw.substring(PREFIX.length());
        int sep = rest.indexOf('_');
        if (sep <= 0 || sep == rest.length() - 1) return Optional.empty();
        String dayStr = rest.substring(0, sep);
        String timeStr = rest.substring(sep + 1);
        DayOfWeek day = parseDay(dayStr);
        if (day == null) return Optional.empty();
        LocalTime time;
        try {
            time = LocalTime.parse(timeStr);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
        return Optional.of(new WeeklyScheduleSpec(day, time));
    }

    /**
     * Serialize back to the wire format. Hours/minutes are zero-padded
     * to match the {@link LocalTime#toString()} convention so a parse →
     * toSpecString round-trip is stable.
     */
    public String toSpecString() {
        return PREFIX + DAY_SHORT[day.getValue() - 1] + "_" + time.toString();
    }

    /** Mon=0, Sun=6 mapping matching DayOfWeek.getValue() - 1. */
    private static final String[] DAY_SHORT = {
            "MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"
    };

    private static DayOfWeek parseDay(String s) {
        if (s == null) return null;
        return switch (s.toUpperCase()) {
            case "MON" -> DayOfWeek.MONDAY;
            case "TUE" -> DayOfWeek.TUESDAY;
            case "WED" -> DayOfWeek.WEDNESDAY;
            case "THU" -> DayOfWeek.THURSDAY;
            case "FRI" -> DayOfWeek.FRIDAY;
            case "SAT" -> DayOfWeek.SATURDAY;
            case "SUN" -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }

    /** Convenience for building a spec from inputs without going through string serialization first. */
    public static WeeklyScheduleSpec of(DayOfWeek day, int hour, int minute) {
        return new WeeklyScheduleSpec(day, LocalTime.of(hour, minute));
    }
}
