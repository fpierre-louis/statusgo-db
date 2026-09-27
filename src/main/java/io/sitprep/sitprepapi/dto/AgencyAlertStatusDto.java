package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.AgencyAlert;

import java.time.Instant;

/** Operator-safe alert history row. Recipient identities and tokens never leave the service. */
public record AgencyAlertStatusDto(
        Long id,
        Long postId,
        String title,
        String officialTier,
        String status,
        int recipientCount,
        int attemptedCount,
        int deliveredCount,
        int failedCount,
        int attemptCount,
        String lastError,
        Instant queuedAt,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt
) {
    public static AgencyAlertStatusDto from(AgencyAlert alert) {
        AgencyAlert.DispatchStatus status = alert.getDispatchStatus() == null
                ? AgencyAlert.DispatchStatus.QUEUED
                : alert.getDispatchStatus();
        return new AgencyAlertStatusDto(
                alert.getId(), alert.getPostId(), alert.getTitle(), alert.getOfficialTier(),
                status.name(),
                alert.getRecipientCount() == null ? 0 : alert.getRecipientCount(),
                alert.getAttemptedCount(), alert.getDeliveredCount(), alert.getFailedCount(),
                alert.getAttemptCount(), alert.getLastError(), alert.getQueuedAt(),
                alert.getStartedAt(), alert.getCompletedAt(), alert.getCreatedAt());
    }
}
