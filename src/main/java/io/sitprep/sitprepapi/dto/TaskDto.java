package io.sitprep.sitprepapi.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.Task;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.util.PublicCdn;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Wire shape for the community feed item. Despite the legacy
 * {@code TaskDto} name, this is the canonical generic-post DTO —
 * {@code kind} (see {@link io.sitprep.sitprepapi.constant.PostKind})
 * carries the discriminator that determines how the row is rendered
 * on the FE: ask / offer / marketplace / tip / recommendation /
 * lost-found / alert-update / blog-promo / post. Image keys are
 * turned into delivery URLs server-side so the frontend just
 * renders. {@code distanceKm} is populated only on community-discover
 * responses; null elsewhere.
 *
 * <p><b>Why this is named "Task" but represents Posts:</b> the
 * underlying entity is {@code Task} for historical reasons (the
 * surface started as community asks, which were modeled as tasks
 * with a requester). The data shape was generalized in the
 * {@code MARKETPLACE_AND_FEED_CALM} pass — same row, just more
 * kinds — but the table + DTO names stayed for migration safety.
 * Phase 3b Session 2 will rename entity {@code Task → Post} (the slot
 * is now free after Phase 3b Session 1 renamed the chat-{@code Post}
 * entity to {@link io.sitprep.sitprepapi.domain.GroupPost}). Table
 * stays {@code task}. See {@code docs/WIP_POST_RENAME.md}.</p>
 *
 * <p>Author profile fields ({@code requesterFirstName},
 * {@code requesterLastName}, {@code requesterProfileImageUrl}) are
 * populated server-side by {@code TaskService} so feed surfaces can
 * render the standard post anatomy (avatar + name + 3-dot menu) without
 * fanning out a separate {@code POST /userinfo/profiles/batch} round
 * trip per page-load. Honors the codebase principle "backend shapes the
 * data, frontend just displays" (per CLAUDE.md). Null when the
 * requester's profile can't be resolved (deleted account, etc.).</p>
 */
public record TaskDto(
        Long id,
        String groupId,
        String requesterEmail,
        String requesterFirstName,
        String requesterLastName,
        String requesterProfileImageUrl,
        String claimedByGroupId,
        String claimedByEmail,
        Task.TaskStatus status,
        Task.TaskPriority priority,
        String title,
        String description,
        Double latitude,
        Double longitude,
        String zipBucket,
        /**
         * Reverse-geocoded place label (neighborhood preferred, city as
         * fallback). Populated server-side at create time so the FE can
         * render a Nextdoor-style "{placeLabel} · {time}" subtitle on
         * each feed card without per-row geocoding. Null when the post
         * is geo-less or the lookup failed — the FE collapses to time-
         * only in that case.
         */
        String placeLabel,
        Instant dueAt,
        Instant createdAt,
        Instant updatedAt,
        Instant claimedAt,
        Instant completedAt,
        Long parentTaskId,
        Set<String> tags,
        List<String> imageKeys,
        List<String> imageUrls,
        /** Filled by community-discover service; null for group/by-me feeds. */
        Double distanceKm,
        // Sponsored fields (SPONSORED_AND_ALERT_MODE step 3). Surface
        // them to the FE so disclosure pills ("Sponsored", "Verified
        // service") render correctly inline on the feed cards.
        boolean sponsored,
        boolean crisisRelevant,
        Instant sponsoredUntil,
        String sponsoredBy,
        // Post-kind vocabulary (MARKETPLACE_AND_FEED_CALM step 1).
        // Lowercase free-form: ask | offer | tip | recommendation |
        // lost-found | alert-update | blog-promo | marketplace.
        String kind,
        // Marketplace-only fields (kind="marketplace"). Null/false on
        // every other kind. SitPrep doesn't process payments; these
        // are pure metadata for the listing card.
        java.math.BigDecimal price,
        boolean isFree,
        /**
         * Parsed payment-method map. Keys: venmo, cashApp, zelle,
         * paypal, applePay, googlePay, cashOnPickup. Values are
         * either a handle string (venmo/cashApp/zelle/paypal) or a
         * boolean accept-flag (applePay/googlePay/cashOnPickup).
         * Empty map when no handles attached or kind != marketplace.
         */
        Map<String, Object> paymentMethods,
        /**
         * True when this row is in the response only because the
         * author is someone the viewer follows — i.e., the post is
         * outside the viewer's chosen radius. Per
         * {@code docs/PROFILE_AND_FOLLOW.md} build-order step 4 the
         * FE renders a "Following · {distance}" subtitle modifier on
         * these cards so the user understands why an out-of-radius
         * post is in their local feed.
         *
         * <p>False on every radius-match (geo-tagged or geo-less),
         * group-feed, by-me feed, etc.</p>
         */
        boolean viaFollow,
        /**
         * Number of heart "Thank" reactions on this task. Folded in by
         * the listing path via a single batched query (see
         * {@code TaskReactionService.loadThankSummary}) so the FE can
         * render the heart count inline on every card without a
         * per-card reaction roundtrip.
         */
        int thanksCount,
        /**
         * True when the requesting viewer has reacted with the heart
         * "Thank" emoji on this task. Drives the filled-vs-outline
         * heart icon in the feed card actions row. Always false for
         * unauthenticated reads (none currently exist on this surface)
         * or when {@code viewerEmail} can't be resolved.
         */
        boolean viewerThanked,
        /**
         * Number of {@code GroupPostComment} rows whose {@code postId == this.id}.
         * Folded in by the listing path via one batched count query so
         * the feed card can render "Reply · N" without fetching the
         * comment list per card. Zero means no replies; the FE renders
         * the icon without a count when this is zero.
         */
        int commentsCount
) {

    /**
     * Entity-only conversion. Author profile fields stay null — the
     * caller (typically {@code TaskService}) is expected to fold in
     * profile data via {@link #withAuthor(UserInfo)} so the FE doesn't
     * need a separate profiles-batch round trip.
     */
    public static TaskDto fromEntity(Task t, Double distanceKm) {
        List<String> keys = t.getImageKeys() == null ? List.of() : t.getImageKeys();
        List<String> urls = keys.stream()
                .map(PublicCdn::toPublicUrl)
                .collect(Collectors.toList());
        return new TaskDto(
                t.getId(),
                t.getGroupId(),
                t.getRequesterEmail(),
                /* requesterFirstName */ null,
                /* requesterLastName */ null,
                /* requesterProfileImageUrl */ null,
                t.getClaimedByGroupId(),
                t.getClaimedByEmail(),
                t.getStatus(),
                t.getPriority(),
                t.getTitle(),
                t.getDescription(),
                t.getLatitude(),
                t.getLongitude(),
                t.getZipBucket(),
                t.getPlaceLabel(),
                t.getDueAt(),
                t.getCreatedAt(),
                t.getUpdatedAt(),
                t.getClaimedAt(),
                t.getCompletedAt(),
                t.getParentTaskId(),
                t.getTags() == null ? Set.of() : t.getTags(),
                keys,
                urls,
                distanceKm,
                t.isSponsored(),
                t.isCrisisRelevant(),
                t.getSponsoredUntil(),
                t.getSponsoredBy(),
                t.getKind(),
                t.getPrice(),
                t.isFree(),
                parsePaymentMethods(t.getPaymentMethodsJson()),
                /* viaFollow */ false,
                /* thanksCount */ 0,
                /* viewerThanked */ false,
                /* commentsCount */ 0
        );
    }

    public static TaskDto fromEntity(Task t) {
        return fromEntity(t, null);
    }

    /**
     * Returns a copy with {@code viaFollow=true}. Used by the community
     * feed merge in {@code TaskService.discoverCommunity} when a post is
     * surfaced because the author is followed by the viewer (out-of-radius
     * pull-through).
     */
    public TaskDto asFollowSource() {
        return new TaskDto(
                id, groupId, requesterEmail,
                requesterFirstName, requesterLastName, requesterProfileImageUrl,
                claimedByGroupId, claimedByEmail, status, priority,
                title, description, latitude, longitude, zipBucket, placeLabel,
                dueAt, createdAt, updatedAt, claimedAt, completedAt,
                parentTaskId, tags, imageKeys, imageUrls, distanceKm,
                sponsored, crisisRelevant, sponsoredUntil, sponsoredBy,
                kind, price, isFree, paymentMethods,
                /* viaFollow */ true,
                thanksCount, viewerThanked, commentsCount
        );
    }

    /**
     * Returns a copy of this DTO with author profile fields populated
     * from {@code u}. Used by {@code TaskService.discoverCommunity}
     * after a batch UserInfo lookup. Profile-image key is converted to
     * a public CDN URL via {@link PublicCdn#toPublicUrl} so the FE just
     * sets {@code <img src=...>}.
     */
    public TaskDto withAuthor(UserInfo u) {
        if (u == null) return this;
        String avatarUrl = u.getProfileImageURL();  // already a URL on UserInfo
        return new TaskDto(
                id,
                groupId,
                requesterEmail,
                u.getUserFirstName(),
                u.getUserLastName(),
                avatarUrl,
                claimedByGroupId,
                claimedByEmail,
                status,
                priority,
                title,
                description,
                latitude,
                longitude,
                zipBucket,
                placeLabel,
                dueAt,
                createdAt,
                updatedAt,
                claimedAt,
                completedAt,
                parentTaskId,
                tags,
                imageKeys,
                imageUrls,
                distanceKm,
                sponsored,
                crisisRelevant,
                sponsoredUntil,
                sponsoredBy,
                kind,
                price,
                isFree,
                paymentMethods,
                viaFollow,
                thanksCount,
                viewerThanked,
                commentsCount
        );
    }

    /**
     * Returns a copy with thank counts + viewer-thanked + comments-count
     * folded in. Used by the listing path so a feed page is one batched
     * query for reaction summary + one for comment counts, rather than
     * N round trips.
     */
    public TaskDto withEngagement(int thanksCount, boolean viewerThanked, int commentsCount) {
        return new TaskDto(
                id, groupId, requesterEmail,
                requesterFirstName, requesterLastName, requesterProfileImageUrl,
                claimedByGroupId, claimedByEmail, status, priority,
                title, description, latitude, longitude, zipBucket, placeLabel,
                dueAt, createdAt, updatedAt, claimedAt, completedAt,
                parentTaskId, tags, imageKeys, imageUrls, distanceKm,
                sponsored, crisisRelevant, sponsoredUntil, sponsoredBy,
                kind, price, isFree, paymentMethods, viaFollow,
                thanksCount, viewerThanked, commentsCount
        );
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    /**
     * Shared Jackson instance for JSON payment-method parsing. Static
     * + final so we don't allocate a new ObjectMapper per row across
     * a 50-row community-feed response.
     */
    private static final ObjectMapper PAYMENT_JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> PAYMENT_TYPE =
            new TypeReference<>() {};

    /**
     * Parse the {@code payment_methods_json} column into a Map for
     * the FE. Bad/empty JSON returns an empty map rather than null
     * so consumers can safely call {@code .get("venmo")} without a
     * null check.
     */
    static Map<String, Object> parsePaymentMethods(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return Map.of();
        try {
            Map<String, Object> parsed = PAYMENT_JSON.readValue(jsonText, PAYMENT_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
