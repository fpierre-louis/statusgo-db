package io.sitprep.sitprepapi.dto;

import java.time.Instant;

public record SaveUserBillingOverrideRequest(
        String packageName,
        Instant expiresAt,
        String reason
) {}
