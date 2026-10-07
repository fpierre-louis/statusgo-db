package io.sitprep.sitprepapi.readiness;

/** How long a step takes. Labels are exact client copy (en dash). */
public enum TimeBand {
    MIN_2("2 min", 2),
    MIN_5("5 min", 5),
    MIN_10("10 min", 10),
    MIN_15("15 min", 15),
    MIN_20_30("20–30 min", 25);

    private final String label;
    private final int minutes;

    TimeBand(String label, int minutes) {
        this.label = label;
        this.minutes = minutes;
    }

    public String label() { return label; }

    /**
     * The band nearest to {@code minutes}; ties go to the shorter band.
     * Used to turn the FE essentials' minute estimates into a band.
     */
    public static TimeBand nearest(int minutes) {
        TimeBand best = MIN_2;
        for (TimeBand b : values()) {
            if (Math.abs(b.minutes - minutes) < Math.abs(best.minutes - minutes)) best = b;
        }
        return best;
    }
}
