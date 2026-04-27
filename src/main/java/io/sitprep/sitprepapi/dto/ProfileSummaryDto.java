package io.sitprep.sitprepapi.dto;

import java.time.Instant;

public record ProfileSummaryDto(
        String email,
        String firstName,
        String lastName,
        String profileImageUrl,
        String userStatus,
        String statusColor,
        Instant userStatusLastUpdated
) {}
