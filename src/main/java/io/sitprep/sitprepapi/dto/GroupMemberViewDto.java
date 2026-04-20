package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;

public record GroupMemberViewDto(
        GroupInfo group,
        String viewerRole,
        List<MemberSummary> members,
        List<PostSummaryDto> recentPosts,
        MetaDto meta
) {

    public record GroupInfo(
            String groupId,
            String name,
            String groupType,
            String description,
            String address,
            String latitude,
            String longitude,
            String zipCode,
            int memberCount,
            String alert,
            Instant createdAt,
            Instant updatedAt,
            String privacy,
            String groupCode,
            String ownerName,
            String ownerEmail,
            List<String> adminEmails,
            List<String> subGroupIds
    ) {}

    public record MemberSummary(
            String email,
            String firstName,
            String lastName,
            String profileImageUrl,
            SelfStatus selfStatus
    ) {}

    public record SelfStatus(
            String value,
            String color,
            Instant updatedAt
    ) {}

    public record MetaDto(
            Instant generatedAt,
            int version
    ) {}
}
