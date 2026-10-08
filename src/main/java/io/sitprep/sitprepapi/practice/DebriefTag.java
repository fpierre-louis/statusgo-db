package io.sitprep.sitprepapi.practice;

import java.util.Arrays;
import java.util.Optional;

/**
 * The closed debrief vocabulary. Practice debriefs are semantic tags, never a
 * score: a content file may only use tags listed here, and writes the copy for
 * each one itself (so wording is versioned with the content).
 *
 * <p>Wire names are lower snake case and are a persistence contract —
 * {@code scenario_run.debrief_tags} stores them.</p>
 */
public enum DebriefTag {
    // Adult scenarios — Communications Outage
    COMMUNICATED_CLEARLY(Polarity.STRONG),
    USED_OUT_OF_AREA_CONTACT(Polarity.STRONG),
    USED_HOUSEHOLD_PLAN(Polarity.STRONG),
    RECOGNIZED_OFFICIAL_SOURCE(Polarity.STRONG),
    NEEDS_CONTACT_REVIEW(Polarity.PRACTICE),
    NEEDS_MEETING_PLACE_REVIEW(Polarity.PRACTICE),
    NEEDS_ROUTE_REVIEW(Polarity.PRACTICE),

    // Family Practice — Kit Builder
    IDENTIFIED_CORE_KIT_ITEMS(Polarity.STRONG),
    DISCUSSED_HOUSEHOLD_SPECIFIC_NEEDS(Polarity.STRONG),
    NEEDS_HOME_KIT_REVIEW(Polarity.PRACTICE);

    /** Which debrief section a tag lands in: "Strong choices" or "Worth practicing". */
    public enum Polarity { STRONG, PRACTICE }

    private final Polarity polarity;

    DebriefTag(Polarity polarity) {
        this.polarity = polarity;
    }

    public Polarity polarity() {
        return polarity;
    }

    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static Optional<DebriefTag> fromWire(String wire) {
        if (wire == null) return Optional.empty();
        return Arrays.stream(values()).filter(t -> t.wire().equals(wire)).findFirst();
    }
}
