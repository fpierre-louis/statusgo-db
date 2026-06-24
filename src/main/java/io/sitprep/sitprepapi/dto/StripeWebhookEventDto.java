package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.StripeWebhookEvent;

import java.time.Instant;

public record StripeWebhookEventDto(
        String stripeEventId,
        String eventType,
        boolean liveMode,
        String status,
        String groupId,
        String detail,
        Instant receivedAt,
        Instant processedAt
) {
    public static StripeWebhookEventDto from(StripeWebhookEvent event) {
        return new StripeWebhookEventDto(
                event.getStripeEventId(),
                event.getEventType(),
                event.isLiveMode(),
                event.getStatus(),
                event.getGroupId(),
                event.getDetail(),
                event.getReceivedAt(),
                event.getProcessedAt()
        );
    }
}
