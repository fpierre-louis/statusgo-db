package io.sitprep.sitprepapi.dto;

import java.time.Instant;

public record HouseholdManualMemberDto(
        String id,
        String householdId,
        String name,
        String relationship,
        Integer age,
        String photoUrl,
        Instant createdAt,
        Instant updatedAt
) {}
