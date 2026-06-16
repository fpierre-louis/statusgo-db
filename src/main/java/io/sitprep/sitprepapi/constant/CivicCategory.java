package io.sitprep.sitprepapi.constant;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Category of a civic_report community post — the infrastructure issue
 * a resident is reporting. Drives the category pill + icon on the card.
 * Stored as the wire string on {@code Post.civicCategory}.
 *
 * <p>Community redesign — see {@code docs/design_handoff_community/backend/CONTRACT.md}.</p>
 */
public enum CivicCategory {

    POTHOLE("pothole"),
    STREETLIGHT("streetlight"),
    DEBRIS("debris"),
    WATER("water"),
    OTHER("other");

    private final String wire;

    CivicCategory(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static final Set<String> ALLOWED_WIRE_VALUES = Stream.of(values())
            .map(CivicCategory::wire)
            .collect(Collectors.toUnmodifiableSet());

    public static boolean isValid(String value) {
        return value != null && ALLOWED_WIRE_VALUES.contains(value);
    }

    public static CivicCategory fromWire(String wire) {
        if (wire == null) return null;
        for (CivicCategory c : values()) {
            if (c.wire.equals(wire)) return c;
        }
        return null;
    }
}
