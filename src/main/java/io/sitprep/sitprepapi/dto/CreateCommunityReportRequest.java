package io.sitprep.sitprepapi.dto;

public record CreateCommunityReportRequest(
        String targetType,
        Long targetId,
        String reason,
        String details
) {}
