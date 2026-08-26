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
        /**
         * The circle's own type — Household · Business · HOA/Neighborhood ·
         * Church · School … Free-form VARCHAR with no enum and no server
         * validation, so treat it as a label, not a closed set.
         *
         * <p><b>POSITION IS LOAD-BEARING AND THIS COMPONENT HAS ALREADY MOVED
         * ONCE.</b> It was first declared between {@code viewerRole} and
         * {@code ownerUserId} while every construction site passed it AFTER
         * {@code ownerUserId}. Both are {@code String}, so javac accepted the
         * swap and the build was green — and production served
         * {@code ownerUserId: "Business"}, pointing the agency follow CTA at a
         * group type instead of a user id. Adding a component between two of
         * the same type in a positional record is a silent argument shift. If
         * you add one, put it at the END, or re-read every call site.</p>
         *
         * <p>Null for every non-group family.</p>
         */
        String groupType,

        // ── mutual-aid (community Post) ─────────────────────────────────
        Long postId,
        String kind,        // offer | marketplace | resource
        String description,
        String placeLabel,

        // ── external POI (Phase 2 — Overpass / FEMA) ────────────────────
        String category,
        String website,
        String externalMapUrl,
        String attribution,  // REQUIRED for external sources (OSM/FEMA license line)

        /**
         * The circle's own uploaded logo — {@code Group.logoImageUrl}.
         *
         * <p><b>Added 2026-08-26 so the map can draw a real identity.</b> Every
         * other group surface renders through
         * {@code GroupTypeIllustration}, whose cascade is
         * {@code logoImageUrl → illustrated emblem → glyph}. The map could
         * reach only the last rung, because this DTO carried no image field at
         * all — so a city with a real seal on file drew a generic building.</p>
         *
         * <p>Null for every non-group family, and null for a group that has not
         * uploaded one. <b>Null renders NOTHING</b> — the client falls to the
         * emblem. It must never render an empty frame: a labelled slot with no
         * value asserts the record has one and it is blank.</p>
         *
         * <p>Declared LAST, deliberately. See the {@code groupType} note above:
         * inserting a {@code String} component between two other
         * {@code String}s in a positional record is a silent argument shift
         * that javac accepts and production serves.</p>
         */
        String logoImageUrl
) {}
