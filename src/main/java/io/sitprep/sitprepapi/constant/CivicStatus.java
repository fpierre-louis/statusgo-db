package io.sitprep.sitprepapi.constant;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Lifecycle status of a civic_report community post (a resident
 * infrastructure report tagging a verified agency). The tagged agency
 * advances it forward; the card's status pill colors by value
 * (reported = muted, acknowledged = blue, scheduled = amber,
 * resolved = green). Stored as the wire string on {@code Post.civicStatus}.
 *
 * <p>Forward-only: a post may move to any status whose {@link #ordinal()}
 * is strictly greater than its current one (and jump straight to
 * RESOLVED). {@link #canAdvanceTo(CivicStatus, CivicStatus)} is the
 * single guard used by the agency PATCH endpoint.</p>
 *
 * <p>Community redesign — see {@code docs/design_handoff_community/backend/CONTRACT.md}.</p>
 */
public enum CivicStatus {

    REPORTED("reported"),
    ACKNOWLEDGED("acknowledged"),
    SCHEDULED("scheduled"),
    RESOLVED("resolved");

    private final String wire;

    CivicStatus(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static final Set<String> ALLOWED_WIRE_VALUES = Stream.of(values())
            .map(CivicStatus::wire)
            .collect(Collectors.toUnmodifiableSet());

    public static boolean isValid(String value) {
        return value != null && ALLOWED_WIRE_VALUES.contains(value);
    }

    public static CivicStatus fromWire(String wire) {
        if (wire == null) return null;
        for (CivicStatus s : values()) {
            if (s.wire.equals(wire)) return s;
        }
        return null;
    }

    /** Forward-only transition guard (any strictly-later status is allowed). */
    public static boolean canAdvanceTo(CivicStatus from, CivicStatus to) {
        if (from == null || to == null) return false;
        return to.ordinal() > from.ordinal();
    }
}
