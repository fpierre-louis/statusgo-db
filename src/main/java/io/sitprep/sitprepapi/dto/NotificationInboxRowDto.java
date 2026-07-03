package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.NotificationLog;

import java.time.Instant;

public record NotificationInboxRowDto(
        Long id,
        String recipientEmail,
        String type,
        String notificationType,
        String token,
        String title,
        String body,
        String referenceId,
        String targetUrl,
        String additionalData,
        Instant timestamp,
        boolean success,
        String errorMessage,
        Instant readAt,
        String lane,
        String category,
        Instant archivedAt,
        String actorUserId
) {
    public static NotificationInboxRowDto from(NotificationLog row) {
        if (row == null) return null;
        return new NotificationInboxRowDto(
                row.getId(),
                row.getRecipientEmail(),
                row.getType(),
                row.getType(),
                row.getToken(),
                row.getTitle(),
                row.getBody(),
                row.getReferenceId(),
                row.getTargetUrl(),
                row.getAdditionalData(),
                row.getTimestamp(),
                row.isSuccess(),
                row.getErrorMessage(),
                row.getReadAt(),
                row.getLane(),
                row.getCategory(),
                row.getArchivedAt(),
                row.getActorUserId()
        );
    }
}
