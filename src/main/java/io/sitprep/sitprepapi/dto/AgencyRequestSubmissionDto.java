package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/** One-time handoff returned when a public agency request is first created. */
public record AgencyRequestSubmissionDto(
        Long id,
        String agencyName,
        String status,
        String statusToken,
        Instant statusTokenExpiresAt,
        Instant submittedAt
) {}
