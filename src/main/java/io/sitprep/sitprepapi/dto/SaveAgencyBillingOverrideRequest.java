package io.sitprep.sitprepapi.dto;

import java.time.Instant;

public record SaveAgencyBillingOverrideRequest(
        String tier,
        Instant expiresAt,
        String reason
) {}
