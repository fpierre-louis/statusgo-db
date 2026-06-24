package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;

public record AdminBillingOperationsDto(
        boolean stripeConfigured,
        boolean webhookConfigured,
        String stripeMode,
        boolean checkoutReady,
        List<PriceConfiguration> prices,
        WebhookHealth webhook,
        List<StripeWebhookEventDto> recentEvents
) {
    public record PriceConfiguration(
            String tier,
            boolean configured
    ) {}

    public record WebhookHealth(
            String status,
            Instant lastReceivedAt,
            Instant lastProcessedAt,
            Instant lastFailedAt,
            long processedLast24Hours,
            long failedLast24Hours
    ) {}
}
