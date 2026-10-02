package io.sitprep.sitprepapi.constant;

/**
 * The three local-time windows a daily brief can post in
 * (docs/epics/daily_summary_gameplan.md §3.2, owner-approved 2026-10-01).
 *
 * <p>The slot changes what a reading looks AHEAD at: morning and midday weigh
 * today's high, evening weighs tonight's low. See {@link ConditionTiers}.</p>
 */
public enum BriefSlot {
    MORNING(7, 0),
    MIDDAY(12, 0),
    EVENING(18, 0);

    private final int hour;
    private final int minute;

    BriefSlot(int hour, int minute) {
        this.hour = hour;
        this.minute = minute;
    }

    public int hour() { return hour; }
    public int minute() { return minute; }
}
