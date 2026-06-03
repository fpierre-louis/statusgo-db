package io.sitprep.sitprepapi.dto;

import java.time.Instant;

public record HouseholdPetDto(
        String id,
        String householdId,
        String name,
        String species,
        String notes,
        String photoUrl,
        Instant createdAt,
        Instant updatedAt
) {}
