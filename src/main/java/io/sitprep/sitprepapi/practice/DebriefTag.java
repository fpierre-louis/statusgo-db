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

    // Adult scenarios — Flash Flood: Turn Around Route
    AVOIDED_FLOODWATER(Polarity.STRONG),
    FOLLOWED_ROAD_CLOSURE(Polarity.STRONG),
    NEEDS_FLOODWATER_RULE(Polarity.PRACTICE), // Flash Flood v3: no one walks or drives into floodwater

    // Adult scenarios — Earthquake: First Five Minutes
    PROTECTED_DURING_SHAKING(Polarity.STRONG),
    CHECKED_IMMEDIATE_HAZARDS(Polarity.STRONG),
    PREPARED_FOR_AFTERSHOCK(Polarity.STRONG),
    NEEDS_EARTHQUAKE_DRILL(Polarity.PRACTICE),

    // Adult scenarios — Evacuating with Pets
    EVACUATED_WITH_PETS(Polarity.STRONG),
    FOUND_HIDING_PET(Polarity.STRONG),
    ARRANGED_PET_PLACE(Polarity.STRONG),
    NEEDS_PET_PLAN(Polarity.PRACTICE),
    NEEDS_CARRIER_PRACTICE(Polarity.PRACTICE),

    // Adult scenarios — Tornado Warning: Shelter Decision (EXEC-I6)
    SHELTERED_LOWEST_INTERIOR(Polarity.STRONG),
    PROTECTED_FROM_DEBRIS(Polarity.STRONG),
    NEEDS_TORNADO_DRILL(Polarity.PRACTICE),
    NEEDS_UPDATE_CHECK(Polarity.PRACTICE),

    // Adult scenarios — House Fire at Night (CT-06)
    GOT_LOW_AND_OUT(Polarity.STRONG),
    CHECKED_DOOR_FIRST(Polarity.STRONG),
    STAYED_OUT_TOLD_FIREFIGHTERS(Polarity.STRONG),
    NEEDS_FIRE_EXIT_DRILL(Polarity.PRACTICE),

    // Family Practice — Kit Builder
    DISCUSSED_CORE_KIT_ITEMS(Polarity.STRONG),
    DISCUSSED_HOUSEHOLD_SPECIFIC_NEEDS(Polarity.STRONG),
    NEEDS_HOME_KIT_REVIEW(Polarity.PRACTICE),

    // Family Practice — Weather Watch
    DISCUSSED_CHANGING_ALERTS(Polarity.STRONG),
    DISCUSSED_OFFICIAL_SOURCES(Polarity.STRONG),

    // Family Practice — Safe Room Detective
    DISCUSSED_SAFE_PLACES(Polarity.STRONG),
    DISCUSSED_LEAVING_WHEN_TOLD(Polarity.STRONG),

    // Together (FR-8) — Get out and meet up
    PRACTICED_HOME_FIRE_ESCAPE(Polarity.STRONG),

    // Family Practice — Message Relay
    DISCUSSED_CHECK_IN_MESSAGE(Polarity.STRONG),
    DISCUSSED_OUT_OF_TOWN_CONTACT(Polarity.STRONG);

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
