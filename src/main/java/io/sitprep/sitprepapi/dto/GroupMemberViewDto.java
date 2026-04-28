package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;

public record GroupMemberViewDto(
        GroupInfo group,
        String viewerRole,
        List<MemberSummary> members,
        /**
         * Household-only — populated when {@code group.groupType == "Household"},
         * empty otherwise. Manual members are children/elders without app
         * accounts.
         */
        List<HouseholdManualMemberDto> manualMembers,
        /**
         * Active "with me" claims inside this household. Empty for
         * non-household groups.
         */
        List<HouseholdAccompanimentDto> accompaniments,
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
            SelfStatus selfStatus,
            /** Last verified-token request from this member; null if never. */
            Instant lastActiveAt,
            /** Last reported device location; null until permission granted. */
            Double lastKnownLat,
            Double lastKnownLng,
            Instant lastKnownLocationAt
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
