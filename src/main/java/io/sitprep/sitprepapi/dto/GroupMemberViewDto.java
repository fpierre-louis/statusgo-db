package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;

public record GroupMemberViewDto(
        GroupInfo group,
        String viewerRole,
        /**
         * The viewer's PLATFORM role ({@code PlatformRole} name — e.g.
         * {@code "SUPER_ADMIN"}, {@code "ADMIN"}), or null when they hold no
         * active {@code platform_admin} row. Lane B3 of
         * docs/audits/2026-07-30-agency-admin-management/FINDINGS.md.
         *
         * <p><b>This is a VISIBILITY signal, not an authorization one.</b> It
         * exists so the platform console's "Dashboard" link stops refusing its
         * own super-admin: {@link #viewerRole} is derived purely from the group
         * roster, so a platform admin who is not personally on the agency's
         * roster resolves to {@code "none"} and the admin dashboard renders
         * "Admin access required".</p>
         *
         * <p>It is shipped as a SEPARATE field rather than by elevating
         * {@code viewerRole} deliberately. {@code viewerRole} is consumed by
         * frontend WRITE gates — {@code GroupVerificationPage} branches on
         * OWNER/ADMIN to offer verification-application submission, and
         * {@code OrgAdminDashboard} passes it to the roster manager to decide
         * whether to render promote / demote / remove controls. Those controls
         * call {@code /api/groups/**}, which is gated by
         * {@code GroupResource.requireAdminOf} and has no platform bypass, so
         * elevating {@code viewerRole} would have made the UI offer actions the
         * API refuses. A platform admin's write path for an agency is the
         * platform-scoped {@code /api/admin/agencies/**} routes instead.</p>
         *
         * <p>Consumers must therefore gate only rendering/visibility on this
         * field, never a mutation.</p>
         */
        String viewerPlatformRole,
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
        /**
         * Server-computed accountability rollup — the single source of truth
         * for the "N of M accounted for" metric (Thin-Client Refactor Phase 1).
         * Replaces the client-side tallies that were duplicated (and drifting)
         * across {@code useHouseholdData.counts} and {@code HouseholdCrisisPanel}.
         *
         * <p>Semantics match the canonical FE {@code useHouseholdData.counts}:
         * real members are freshness-clamped (a status older than the alert
         * start is treated as NO RESPONSE while the group's alert is Active);
         * manual members (dependents without accounts) count as accounted only
         * when an adult has claimed them via a "with me" accompaniment.</p>
         */
        StatusRollup rollup,
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

    /**
     * Accountability rollup. {@code total} = real members + manual members;
     * {@code accounted} = safe + help + injured; {@code noResponse} =
     * total − accounted (unreached members + unclaimed dependents).
     */
    public record StatusRollup(
            int total,
            int accounted,
            int safe,
            int help,
            int injured,
            int noResponse
    ) {}

    public record MetaDto(
            Instant generatedAt,
            int version
    ) {}
}
