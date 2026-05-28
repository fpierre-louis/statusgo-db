package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;

/**
 * Response shape for {@code GET /api/community/discover}. The frontend
 * Community surface (CommunityDiscoverPage) is a thin renderer over this —
 * server pre-shapes everything: distance, place label, sorted order.
 *
 * <p>First cut surfaces only public groups within the requested radius.
 * Future cuts can extend with: nearby community-scope tasks, active alerts,
 * post snippets — all derivable from the same lat/lng + radius input.</p>
 */
public record CommunityDiscoverDto(
        Place place,
        List<NearbyGroup> nearbyGroups,
        MetaDto meta
) {

    /**
     * Reverse-geocoded label for the viewer's coordinates. Null fields when
     * Nominatim couldn't resolve.
     *
     * <p>{@code neighborhood} (suburb / quarter / neighbourhood from OSM) is
     * the most-local-resolvable label, useful for Nextdoor-style headers
     * ("Murdock Trail" vs the broader city). FE consumers fall back through
     * neighborhood → city → town → village → state.</p>
     */
    public record Place(
            String neighborhood,
            String city,
            String region,
            String state,
            String country,
            String zipBucket
    ) {}

    public record NearbyGroup(
            String groupId,
            String name,
            String groupType,
            String description,
            int memberCount,
            double distanceKm,
            String latitude,
            String longitude,
            String address,
            String zipCode,
            String alert,
            String privacy,
            /**
             * True when the viewer (per {@code viewerEmail} query param) is
             * already a member, admin, owner, or pending of this group.
             * The default service behavior <em>excludes</em> these from the
             * response — this flag exists so a future "groups you're already
             * in" surface can opt in by passing {@code includeMine=true}.
             * Always false when no viewerEmail is supplied.
             */
            boolean viewerIsMember,
            /**
             * True when the group's owner is a verified publisher (Red
             * Cross, CERT, municipal EM, etc. — controlled via
             * UserInfo.verifiedPublisher). The FE renders a checkmark
             * next to the group name to signal "this is an org you
             * can trust." Owner-keyed because verification follows the
             * publisher's identity, not a per-group toggle.
             */
            boolean verified,
            /** Trust-tier kind (e.g. "Red_Cross", "Government") when {@link #verified}; null otherwise. */
            String verifiedKind,
            /**
             * How many of this group's members are ALSO in at least one
             * of the viewer's other groups — the "you know N people
             * here" social signal. Always 0 when no viewerEmail is
             * supplied. Cardinality is bounded by group size, so this
             * stays a single in-memory set intersection per group.
             */
            int mutuals,
            /**
             * Timestamp of the most recent chat post in this group, or
             * Group.updatedAt when the group has no posts yet — mirrors
             * the lastActivityAt semantics on MeDto.GroupSummary. The FE
             * uses this to render the "active 12m ago" / "quiet · 4d"
             * activity dot on Discover cards.
             */
            Instant lastActivityAt
    ) {}

    public record MetaDto(
            Instant generatedAt,
            int version,
            int totalConsidered,
            int returned
    ) {}
}
