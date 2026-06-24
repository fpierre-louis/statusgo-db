package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.AdminAuditLog;

import java.time.Instant;

public record AdminAuditLogDto(
        Long id,
        String actorEmail,
        String action,
        String targetType,
        String targetId,
        String summary,
        Instant at
) {
    public static AdminAuditLogDto from(AdminAuditLog row) {
        return new AdminAuditLogDto(
                row.getId(),
                row.getActorEmail(),
                row.getAction(),
                row.getTargetType(),
                row.getTargetId(),
                row.getSummary(),
                row.getAt());
    }
}
