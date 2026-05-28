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
        List<GroupPostSummaryDto> recentPosts,
        /**
         * Posts pinned to the top of this group's feed, newest-pin
         * first. Empty list when nothing is pinned — never null. A
         * pinned post may also appear in {@link #recentPosts} if it's
         * recent enough to fall inside the recent-posts limit; the FE
         * de-dupes by id so the inline feed doesn't double-render it.
         * Cardinality is small in practice (admins typically pin 0-3).
         */
        List<GroupPostSummaryDto> pinnedPosts,
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
            List<String> subGroupIds,
            /** Org plan tier enum name (PlanTier); null reads as FREE. */
            String planTier
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
