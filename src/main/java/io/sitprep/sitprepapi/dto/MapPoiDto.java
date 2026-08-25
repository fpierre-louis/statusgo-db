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
        /**
         * The circle's own type — Household · Business · HOA/Neighborhood ·
         * Church · School … Free-form VARCHAR with no enum and no server
         * validation, so treat it as a label, not a closed set.
         *
         * <p><b>Added 2026-08-25 because the map could not tell circles
         * apart.</b> Every {@code family:"group"} poi drew one identical mark —
         * a household, a business and an HOA were the same pin — for the plain
         * reason that nothing told the layer which kind it was drawing. The
         * defect was stated on device as "the pins look identical", which is
         * the symptom at the wrong layer: four distinct marks would still have
         * rendered one of them four times.</p>
         *
         * <p>Null for every non-group family.</p>
         */
        String groupType,
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
