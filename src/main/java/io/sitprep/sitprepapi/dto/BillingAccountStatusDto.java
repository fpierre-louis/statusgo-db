package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;

public record BillingAccountStatusDto(
        boolean stripeConfigured,
        String stripeMode,
        List<String> checkoutAvailableTiers,
        boolean customerPresent,
        boolean subscriptionPresent,
        boolean portalAvailable,
        String subscriptionStatus,
        String basePlanTier,
        String effectivePlanTier,
        String overrideTier,
        Instant overrideExpiresAt,
        boolean overrideActive
) {}
