package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.HouseholdRitual;

import java.time.Instant;

/**
 * Wire shape for {@link HouseholdRitual}. Flat record — the FE just
 * reads scheduleSpec + kind to decide whether to show "Weekly check-in
 * set" vs "Set a weekly check-in". v2's picker UI will additionally
 * parse scheduleSpec to render the day/time the admin chose.
 */
public record HouseholdRitualDto(
        Long id,
        String householdId,
        String kind,
        String scheduleSpec,
        String timezone,
        String optedInByEmail,
        Instant lastFiredAt,
        Instant pausedUntil,
        Instant createdAt,
        Instant updatedAt
) {
    public static HouseholdRitualDto from(HouseholdRitual r) {
        if (r == null) return null;
        return new HouseholdRitualDto(
                r.getId(),
                r.getHouseholdId(),
                r.getKind(),
                r.getScheduleSpec(),
                r.getTimezone(),
                r.getOptedInByEmail(),
                r.getLastFiredAt(),
                r.getPausedUntil(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
