package io.sitprep.sitprepapi.constant;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tier of a verified-agency "official" community post. Drives the card's
 * accent color + tier pill on the feed (emergency = red, advisory =
 * amber, notice = blue). Stored as the wire string on {@code Post.officialTier}
 * (a String column, mirroring {@code Post.kind}); this enum is the typed
 * source of truth + validation. Community redesign — see
 * {@code docs/design_handoff_community/backend/CONTRACT.md}.
 */
public enum OfficialTier {

    /** Highest urgency — pins to the top of the feed within radius. */
    EMERGENCY("emergency"),

    /** Public advisory (boil-water, evacuation watch). */
    ADVISORY("advisory"),

    /** Routine city notice (road closure, detour). */
    NOTICE("notice");

    private final String wire;

    OfficialTier(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static final Set<String> ALLOWED_WIRE_VALUES = Stream.of(values())
            .map(OfficialTier::wire)
            .collect(Collectors.toUnmodifiableSet());

    public static boolean isValid(String value) {
        return value != null && ALLOWED_WIRE_VALUES.contains(value);
    }

    public static OfficialTier fromWire(String wire) {
        if (wire == null) return null;
        for (OfficialTier t : values()) {
            if (t.wire.equals(wire)) return t;
        }
        return null;
    }
}
