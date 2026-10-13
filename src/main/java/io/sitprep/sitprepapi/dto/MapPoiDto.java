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
        String logoImageUrl,

        // ── BE-5 (2026-09-27) — appended, see the positional warning above ──

        /**
         * Why this pin is flagged as a priority, as a server-authored code.
         * Exactly one reason exists: {@code "poster-urgent"} when the post's
         * own {@code priority} is {@code URGENT}. Null otherwise — no other
         * reason is inferred (not from wording, not from alerts).
         */
        String priorityReason,
        /** When the source row was created: the post, or the group. Null for external POIs. */
        java.time.Instant createdAt,
        /**
         * Who posted an aid/ask pin: the author's "First Last", or the group's
         * name when the post speaks as a group. A name or null — never an
         * email or its local-part. Null for signed-out viewers.
         */
        String authorDisplayName,
        /**
         * Agencies only: true iff the group holds the V80
         * {@code SEND_AREA_ALERTS} capability. Null for every non-agency pin.
         * Says nothing about WHICH area — agency↔area matching is unbacked.
         */
        Boolean canSendAreaAlerts,
        /**
         * "Still here?" (V85): distinct people who confirmed this place in the
         * last 7 days, and the latest time — aid posts and OSM places only.
         * Null when nobody has, and for every other family. Unrelated to the
         * post's "Me too" count.
         */
        MapConfirmationDtos.ConfirmationSummary confirmations,

        // ── P2b (2026-10-13) — appended ──────────────────────────────────

        /**
         * Aid posts only: the post's first photo as a public CDN URL
         * ({@code PublicCdn.toPublicUrl}), else null. The map draws it as the
         * report card's photo (map pins v2). Signed-in viewers only, because
         * the feed card that already shows this photo is signed-in only. It
         * carries no author identity, so a hidden-author post may still have
         * one. Null renders NO frame: the client draws the post's usual pin.
         */
        String imageUrl
) {
    /*
     * HOW TO BUILD ONE: {@code MapPoiDto.builder().id(..).family(..)...build()}.
     *
     * The canonical constructor stays public because Java requires it for a
     * public record (and Jackson binds it when ExternalPoiCacheService reads a
     * cached tile). Do NOT call it from service code: 28 positional arguments,
     * twelve of them String, is how {@code ownerUserId:"Business"} shipped.
     * MapPoiDtoJsonShapeTest has a source guard that fails the build on a new
     * {@code new MapPoiDto(} outside this file.
     */

    /** A builder with every field null. */
    public static Builder builder() {
        return new Builder();
    }

    /** A builder pre-filled with this row's fields (records are immutable). */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.id = id; b.family = family; b.source = source; b.name = name;
        b.lat = lat; b.lng = lng; b.distanceKm = distanceKm;
        b.verified = verified; b.verifiedKind = verifiedKind; b.memberCount = memberCount;
        b.viewerRole = viewerRole; b.ownerUserId = ownerUserId; b.groupType = groupType;
        b.postId = postId; b.kind = kind; b.description = description; b.placeLabel = placeLabel;
        b.category = category; b.website = website; b.externalMapUrl = externalMapUrl;
        b.attribution = attribution; b.logoImageUrl = logoImageUrl;
        b.priorityReason = priorityReason; b.createdAt = createdAt;
        b.authorDisplayName = authorDisplayName; b.canSendAreaAlerts = canSendAreaAlerts;
        b.confirmations = confirmations; b.imageUrl = imageUrl;
        return b;
    }

    /**
     * The pre-BE-5 shape: every appended field null.
     *
     * @deprecated positional; use {@link #builder()}. Its one remaining caller
     * is {@code PlanActivationService.planPoi}, left alone on 2026-10-13
     * because another lane owned that file. Migrate it, then delete this.
     */
    @Deprecated
    public MapPoiDto(String id, String family, String source, String name,
                     Double lat, Double lng, Double distanceKm,
                     Boolean verified, String verifiedKind, Integer memberCount,
                     String viewerRole, String ownerUserId, String groupType,
                     Long postId, String kind, String description, String placeLabel,
                     String category, String website, String externalMapUrl, String attribution,
                     String logoImageUrl) {
        this(id, family, source, name, lat, lng, distanceKm,
                verified, verifiedKind, memberCount, viewerRole, ownerUserId, groupType,
                postId, kind, description, placeLabel,
                category, website, externalMapUrl, attribution,
                logoImageUrl,
                null, null, null, null, null,
                null);
    }

    /** Copy with {@link #confirmations} set. */
    public MapPoiDto withConfirmations(MapConfirmationDtos.ConfirmationSummary c) {
        return toBuilder().confirmations(c).build();
    }

    /** Named setters, one per component, so no two same-typed fields can swap. */
    public static final class Builder {
        private String id, family, source, name;
        private Double lat, lng, distanceKm;
        private Boolean verified;
        private String verifiedKind;
        private Integer memberCount;
        private String viewerRole, ownerUserId, groupType;
        private Long postId;
        private String kind, description, placeLabel;
        private String category, website, externalMapUrl, attribution;
        private String logoImageUrl;
        private String priorityReason;
        private java.time.Instant createdAt;
        private String authorDisplayName;
        private Boolean canSendAreaAlerts;
        private MapConfirmationDtos.ConfirmationSummary confirmations;
        private String imageUrl;

        private Builder() {}

        public Builder id(String v) { id = v; return this; }
        public Builder family(String v) { family = v; return this; }
        public Builder source(String v) { source = v; return this; }
        public Builder name(String v) { name = v; return this; }
        public Builder lat(Double v) { lat = v; return this; }
        public Builder lng(Double v) { lng = v; return this; }
        public Builder distanceKm(Double v) { distanceKm = v; return this; }
        public Builder verified(Boolean v) { verified = v; return this; }
        public Builder verifiedKind(String v) { verifiedKind = v; return this; }
        public Builder memberCount(Integer v) { memberCount = v; return this; }
        public Builder viewerRole(String v) { viewerRole = v; return this; }
        public Builder ownerUserId(String v) { ownerUserId = v; return this; }
        public Builder groupType(String v) { groupType = v; return this; }
        public Builder postId(Long v) { postId = v; return this; }
        public Builder kind(String v) { kind = v; return this; }
        public Builder description(String v) { description = v; return this; }
        public Builder placeLabel(String v) { placeLabel = v; return this; }
        public Builder category(String v) { category = v; return this; }
        public Builder website(String v) { website = v; return this; }
        public Builder externalMapUrl(String v) { externalMapUrl = v; return this; }
        public Builder attribution(String v) { attribution = v; return this; }
        public Builder logoImageUrl(String v) { logoImageUrl = v; return this; }
        public Builder priorityReason(String v) { priorityReason = v; return this; }
        public Builder createdAt(java.time.Instant v) { createdAt = v; return this; }
        public Builder authorDisplayName(String v) { authorDisplayName = v; return this; }
        public Builder canSendAreaAlerts(Boolean v) { canSendAreaAlerts = v; return this; }
        public Builder confirmations(MapConfirmationDtos.ConfirmationSummary v) { confirmations = v; return this; }
        public Builder imageUrl(String v) { imageUrl = v; return this; }

        /** The ONE place the canonical constructor is called in order. */
        public MapPoiDto build() {
            return new MapPoiDto(id, family, source, name, lat, lng, distanceKm,
                    verified, verifiedKind, memberCount, viewerRole, ownerUserId, groupType,
                    postId, kind, description, placeLabel,
                    category, website, externalMapUrl, attribution,
                    logoImageUrl, priorityReason, createdAt, authorDisplayName, canSendAreaAlerts,
                    confirmations, imageUrl);
        }
    }
}
