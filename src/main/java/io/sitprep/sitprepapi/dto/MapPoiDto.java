package io.sitprep.sitprepapi.dto;

/**
 * Canonical map point-of-interest — the single normalized shape every source
 * (proprietary groups/posts today; Overpass/FEMA in Phase 2) collapses to, so
 * the frontend renders by {@code family} + {@code source} blind to the upstream
 * shape. See docs/COMMUNITY_API_GAMEPLAN.md §5.
 *
 * <p>Boxed types (nullable) because most fields are family-specific: an agency
 * carries {@code verified}/{@code ownerUserId}; an aid post carries
 * {@code postId}/{@code kind}; a future external POI carries
 * {@code category}/{@code website}/{@code attribution}. Unused fields serialize
 * as {@code null}.</p>
 */
public record MapPoiDto(
        String id,          // stable per source, e.g. "group:UUID", "post:123", "overpass:node/1"
        String family,      // agency | group | shelter | park | amenity | aid
        String source,      // proprietary:group | proprietary:post | overpass | fema | nws
        String name,
        Double lat,
        Double lng,
        Double distanceKm,  // from the viewport center

        // ── group / agency ─────────────────────────────────────────────
        Boolean verified,
        String verifiedKind,
        Integer memberCount,
        String viewerRole,  // OWNER | ADMIN | MEMBER | PENDING | NONE (for the Join CTA)
        String ownerUserId, // agency follow target (agencies only; null otherwise)

        // ── mutual-aid (community Post) ─────────────────────────────────
        Long postId,
        String kind,        // offer | marketplace | resource
        String description,
        String placeLabel,

        // ── external POI (Phase 2 — Overpass / FEMA) ────────────────────
        String category,
        String website,
        String externalMapUrl,
        String attribution  // REQUIRED for external sources (OSM/FEMA license line)
) {}
