package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;

/**
 * Response for {@code GET /api/community/map} — the viewport-driven Community
 * Discovery Engine (docs/COMMUNITY_API_GAMEPLAN.md §5).
 *
 * <p>Deliberately omits the reverse-geocoded {@code place} that
 * {@link CommunityDiscoverDto} carries: this endpoint fires on every settled
 * pan/zoom, and a per-request Nominatim reverse-geocode would blow the
 * sub-200ms budget. Place labels come from the feed surface, not the map.</p>
 */
public record MapDiscoveryDto(
        List<MapPoiDto> pois,
        MetaDto meta
) {
    public record MetaDto(
            Instant generatedAt,
            int zoomBand,       // 0 regional · 1 city · 2 neighborhood · 3 street
            int returned,
            boolean capped,     // true when the per-band cap trimmed results
            List<String> sources
    ) {}
}
