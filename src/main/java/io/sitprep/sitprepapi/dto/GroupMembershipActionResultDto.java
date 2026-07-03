package io.sitprep.sitprepapi.dto;

import java.time.Instant;

public record GroupMembershipActionResultDto(
        boolean success,
        String action,
        String status,
        String groupId,
        String email,
        String role,
        int memberCount,
        int pendingMemberCount,
        Instant updatedAt
) {}
