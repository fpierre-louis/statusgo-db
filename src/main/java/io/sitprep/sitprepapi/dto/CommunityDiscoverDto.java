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

    /** Reverse-geocoded label for the viewer's coordinates. Null fields when Nominatim couldn't resolve. */
    public record Place(
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
            String privacy
    ) {}

    public record MetaDto(
            Instant generatedAt,
            int version,
            int totalConsidered,
            int returned
    ) {}
}
