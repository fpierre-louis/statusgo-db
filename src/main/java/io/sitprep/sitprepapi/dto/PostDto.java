package io.sitprep.sitprepapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.util.PublicCdn;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Wire shape for the community feed item. Despite the legacy
 * {@code PostDto} name, this is the canonical generic-post DTO —
 * {@code kind} (see {@link io.sitprep.sitprepapi.constant.PostKind})
 * carries the discriminator that determines how the row is rendered
 * on the FE: ask / offer / marketplace / tip / recommendation /
 * lost-found / alert-update / blog-promo / post. Image keys are
 * turned into delivery URLs server-side so the frontend just
 * renders. {@code distanceKm} is populated only on community-discover
 * responses; null elsewhere.
 *
 * <p><b>Why this is named "Post" but represents Posts:</b> the
 * underlying entity is {@code Post} for historical reasons (the
 * surface started as community asks, which were modeled as tasks
 * with a requester). The data shape was generalized in the
 * {@code MARKETPLACE_AND_FEED_CALM} pass — same row, just more
 * kinds — but the table + DTO names stayed for migration safety.
 * Phase 3b Session 2 will rename entity {@code Post → Post} (the slot
 * is now free after Phase 3b Session 1 renamed the chat-{@code Post}
 * entity to {@link io.sitprep.sitprepapi.domain.GroupPost}). Table
 * stays {@code task}. See {@code docs/WIP_POST_RENAME.md}.</p>
 *
 * <p>Author profile fields ({@code requesterFirstName},
 * {@code requesterLastName}, {@code requesterProfileImageUrl}) are
 * populated server-side by {@code PostService} so feed surfaces can
 * render the standard post anatomy (avatar + name + 3-dot menu) without
 * fanning out a separate {@code POST /userinfo/profiles/batch} round
 * trip per page-load. Honors the codebase principle "backend shapes the
 * data, frontend just displays" (per CLAUDE.md). Null when the
 * requester's profile can't be resolved (deleted account, etc.).</p>
 */
public record PostDto(
        Long id,
        String groupId,
        String requesterEmail,
        String requesterFirstName,
        String requesterLastName,
        String requesterProfileImageUrl,
        String claimedByGroupId,
        String claimedByEmail,
        Post.PostStatus status,
        Post.PostPriority priority,
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
        Long parentPostId,
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
        String authorType,
        String verifiedState,
        String publisherScope,
        String publisherProfileUrl,
        String serviceAreaLabel,
        String jurisdictionLabel,
        String sponsoredDisclosure,
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
         * {@code PostReactionService.loadThankSummary}) so the FE can
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
        int commentsCount,
        /**
         * Per-emoji reaction counts ({@code {"❤":12,"🙏":4,"👍":2}}).
         * Folded in by the listing path via {@code PostReactionService.loadReactionSummary}
         * so the FE can render multi-emoji clusters under each post
         * without a per-card reactions fetch. Empty / null when the
         * post has no reactions; the FE renders nothing in that case.
         * The legacy {@code thanksCount} above is kept in sync with
         * {@code reactionsByEmoji.get("❤")} for back-compat with FE
         * code that hasn't migrated.
         */
        Map<String, Integer> reactionsByEmoji,
        /**
         * Set of emojis the requesting viewer has recorded on this
         * post. Lets the FE highlight the viewer's chosen emoji in
         * the picker + the cluster row. Empty for unauthenticated
         * reads or when the viewer hasn't reacted.
         */
        Set<String> viewerEmojis,
        /**
         * Most recent comment on this post as a compact preview, or
         * {@code null} when the post has no comments. Folded in by the
         * listing path via one batched query. Drives the IG/FB-style
         * "preview the latest reply under the post card" surface on the
         * community feed.
         */
        CommentPreviewDto latestCommentPreview,
        /**
         * When non-null, this post is attributed to a group rather than
         * to the individual requester. The FE swaps the author header
         * (avatar + name) for the group's emblem + name.
         *
         * <p>The {@code authoredAsGroupName} and {@code authoredAsGroupType}
         * fields are denormalized from the matching {@link
         * io.sitprep.sitprepapi.domain.Group} row so the FE has the
         * identity it needs in a single response — no batch
         * group-profiles round-trip per feed page.</p>
         *
         * <p>Stays null on every legacy row (default) + on every post
         * authored by an individual (the common case).</p>
         */
        String authoredAsGroupId,
        String authoredAsGroupName,
        String authoredAsGroupType,
        /**
         * Email of the member this group task is assigned to (push
         * assignment by a group admin), or null when unassigned. See
         * {@link io.sitprep.sitprepapi.domain.Post#assigneeEmail}.
         */
        String assigneeEmail,
        /**
         * Compact preview of the original post when this row is a
         * repost / quote-post. Null for normal posts and for missing
         * parents. This keeps feed/detail cards one-response shaped:
         * the FE can render the quoted card without an N+1 lookup.
         */
        ParentPostPreview parentPost,
        /**
         * Community-redesign per-type fields (feedItemType discriminator,
         * official tier, civic lifecycle + tagged agency, news source,
         * confirms + saved viewer state). One nested record so the FE
         * keys card chrome off {@code community.feedItemType} and each
         * existing PostDto constructor site threads a single extra arg.
         */
        CommunityExtras community,
        // Unified work-order fields (Phase 1). Flat so the FE reads
        // task.liabilityRequired / task.nearPowerLines etc. directly.
        // Meaningful on kind="task" work orders; default false / null on
        // every other kind. safeToEnter is a nullable tri-state
        // (null=unknown, true=yes, false=no).
        boolean liabilityRequired,
        boolean releaseSigned,
        String releaseTextHash,
        String releaseExceptionReason,
        boolean nearPowerLines,
        boolean electricalHazard,
        String waterLevel,
        Boolean safeToEnter,
        /**
         * Dynamic, need-type-specific work-order intake bag — the jsonb
         * {@code work_details} column on the entity (V47). Sparse map captured
         * by the {@code WorkOrderWizard} Site &amp; Triage step (numberOfTrees,
         * occupancy, dietaryNotes, hazardNotes, …), keyed off {@code needType}.
         * Null/absent on personal tasks and every non-work-order kind. Ingested
         * from the create body and returned verbatim so the FE round-trips the
         * same shape it sent — "backend shapes the data, frontend just displays".
         *
         * <p>{@code @JsonInclude(NON_NULL)}: null (personal tasks / non-work-order
         * kinds) is omitted from the wire so the FE reads "absent === unknown",
         * per the contract's strict-nullability rule.</p>
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Map<String, Object> workDetails,
        /**
         * Denormalized need-type discriminator (V47), mirrored from
         * {@code workDetails.needType} onto its own indexed column. Null on
         * personal tasks / non-work-order kinds; omitted from JSON (NON_NULL) to
         * honor the contract's "absent === unknown" rule.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String needType,
        /**
         * Work-order assignees (Step 2) — the LEAD + any HELPERs — each with a
         * display name + avatar folded server-side via the same batch UserInfo
         * path the requester uses (so Step-A avatars render without the client
         * cache). {@code task_assignee} is authoritative; {@code assigneeEmail}
         * above is the derived display mirror. Empty on non-work-order kinds and
         * on unassigned tasks (never null on the wire — the FE reads a list).
         */
        List<AssigneeDto> assignees,
        /**
         * Bundles / projects (V51) — DERIVED roll-up of this project's children
         * (counts by status + a display label + the triage feed). Non-null only
         * when {@code kind="project"}; omitted from the wire (NON_NULL) on every
         * standalone task and non-task kind. Computed on read; never persisted.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ProjectRollup projectRollup,
        /**
         * Bundles / projects (V51) — this project's child tasks as full task
         * DTOs, folded ONLY on the project-detail path. Null (omitted) on the
         * group list (the project card shows {@code projectRollup} + its
         * {@code total} childCount instead) and on every non-project row. An
         * empty list is a real, distinct value: a project with no tasks yet.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<PostDto> children
) {

    /**
     * One work-order assignee (Step 2): identity + task-level role LEAD|HELPER,
     * plus {@code primary} (Phase 2a) — true for the one lead marked as the point
     * of contact when a task has several leads. Always false for Helpers.
     */
    public record AssigneeDto(String email, String displayName, String avatarUrl,
                              String role, boolean primary) {}

    /**
     * Bundles / projects (V51) — the DERIVED roll-up of a project's children,
     * computed from child rows on read (never persisted). {@code total} doubles
     * as the childCount the list card shows. The triage feed
     * ({@code topOpenPriority}, {@code anyOpenLifeSafety},
     * {@code mostUrgentOpenChildId}) lets the mixed list float a project up by
     * its hottest still-open child (a life-safety child surfaces the project).
     * {@code label} is a ready-to-render string ("1/3 done · 1 in progress ·
     * 1 open"). Counts: {@code done} folds DONE + CLOSED (terminal-complete).
     */
    public record ProjectRollup(
            int total, int open, int claimed, int inProgress, int done, int cancelled,
            String label,
            Post.PostPriority topOpenPriority,
            boolean anyOpenLifeSafety,
            Long mostUrgentOpenChildId
    ) {}

    /** Per-type community-feed fields — see {@link #community}. */
    public record CommunityExtras(
            String feedItemType,          // official | civic_report | news | neighbor | sponsored
            String officialTier,          // emergency | advisory | notice (official only)
            String civicCategory,         // pothole | streetlight | debris | water | other
            String civicStatus,           // reported | acknowledged | scheduled | resolved
            TaggedAgency taggedAgency,    // civic_report only; null otherwise
            NewsSource source,            // news only; null otherwise
            Integer readMinutes,          // news only
            int confirmsCount,
            boolean viewerConfirmed,
            boolean viewerSaved,
            // True while this post is pinned to the top of the feed (a
            // severe/emergency alert or an author-pinned official) — for
            // 24h from pinnedAt, or until pinnedUntil. Drives the FE
            // "Pinned by your area" strip. Replaces the old top alert band.
            boolean pinned
    ) {
        public record TaggedAgency(String id, String name, boolean verified, String note) {}
        public record NewsSource(String name, String url) {}

        static CommunityExtras fromEntity(Post t) {
            TaggedAgency agency = isBlank(t.getTaggedAgencyGroupId()) ? null
                    : new TaggedAgency(t.getTaggedAgencyGroupId(), null, false, trim(t.getAgencyNote()));
            NewsSource source = (isBlank(t.getSourceName()) && isBlank(t.getSourceUrl())) ? null
                    : new NewsSource(trim(t.getSourceName()), trim(t.getSourceUrl()));
            Instant now = Instant.now();
            boolean pinned = (t.getPinnedAt() != null && t.getPinnedAt().isAfter(now.minusSeconds(86_400)))
                    || (t.getPinnedUntil() != null && t.getPinnedUntil().isAfter(now));
            return new CommunityExtras(
                    feedItemType(t), trim(t.getOfficialTier()),
                    trim(t.getCivicCategory()), trim(t.getCivicStatus()),
                    agency, source, t.getReadMinutes(),
                    0, false, false, pinned);
        }

        /** Derived discriminator the FE renders card chrome from. */
        static String feedItemType(Post t) {
            if (t.isSponsored()) return "sponsored";
            String kind = t.getKind();
            if (t.getCivicStatus() != null || "civic-report".equals(kind)) return "civic_report";
            if ("news".equals(kind)) return "news";
            if ("official".equals(kind) || !isBlank(t.getOfficialTier())) return "official";
            return "neighbor";
        }

        public CommunityExtras withConfirms(int count, boolean viewer) {
            return new CommunityExtras(feedItemType, officialTier, civicCategory, civicStatus,
                    taggedAgency, source, readMinutes, count, viewer, viewerSaved, pinned);
        }

        public CommunityExtras withSaved(boolean saved) {
            return new CommunityExtras(feedItemType, officialTier, civicCategory, civicStatus,
                    taggedAgency, source, readMinutes, confirmsCount, viewerConfirmed, saved, pinned);
        }

        /** Fold the tagged agency's display name + verified flag (Group lookup). */
        public CommunityExtras withAgencyIdentity(String name, boolean verified) {
            if (taggedAgency == null) return this;
            return new CommunityExtras(feedItemType, officialTier, civicCategory, civicStatus,
                    new TaggedAgency(taggedAgency.id(), name, verified, taggedAgency.note()),
                    source, readMinutes, confirmsCount, viewerConfirmed, viewerSaved, pinned);
        }

        public CommunityExtras withPinned(boolean p) {
            return new CommunityExtras(feedItemType, officialTier, civicCategory, civicStatus,
                    taggedAgency, source, readMinutes, confirmsCount, viewerConfirmed, viewerSaved, p);
        }

        private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    }

    /** Returns a copy with the community extras replaced. */
    public PostDto withCommunity(CommunityExtras c) {
        return new PostDto(
                id, groupId, requesterEmail,
                requesterFirstName, requesterLastName, requesterProfileImageUrl,
                claimedByGroupId, claimedByEmail, status, priority,
                title, description, latitude, longitude, zipBucket, placeLabel,
                dueAt, createdAt, updatedAt, claimedAt, completedAt,
                parentPostId, tags, imageKeys, imageUrls, distanceKm,
                sponsored, crisisRelevant, sponsoredUntil, sponsoredBy,
                authorType, verifiedState, publisherScope, publisherProfileUrl,
                serviceAreaLabel, jurisdictionLabel, sponsoredDisclosure,
                kind, price, isFree, paymentMethods, viaFollow,
                thanksCount, viewerThanked, commentsCount,
                reactionsByEmoji, viewerEmojis, latestCommentPreview,
                authoredAsGroupId, authoredAsGroupName, authoredAsGroupType,
                assigneeEmail, parentPost, c,
                liabilityRequired(), releaseSigned(), releaseTextHash(), releaseExceptionReason(),
                nearPowerLines(), electricalHazard(), waterLevel(), safeToEnter(), workDetails(), needType(),
                assignees, projectRollup, children);
    }

    public record ParentPostPreview(
            Long id,
            String requesterEmail,
            String requesterFirstName,
            String requesterLastName,
            String requesterProfileImageUrl,
            String title,
            String description,
            String kind,
            Instant createdAt,
            String placeLabel,
            List<String> imageUrls,
            String authoredAsGroupId,
            String authoredAsGroupName,
            String authoredAsGroupType
    ) {
        public static ParentPostPreview fromEntity(Post t, UserInfo author, String groupName, String groupType) {
            if (t == null || t.getId() == null) return null;
            List<String> urls = (t.getImageKeys() == null ? List.<String>of() : t.getImageKeys()).stream()
                    .map(PublicCdn::toPublicUrl)
                    .collect(Collectors.toList());
            return new ParentPostPreview(
                    t.getId(),
                    t.getRequesterEmail(),
                    author == null ? null : author.getUserFirstName(),
                    author == null ? null : author.getUserLastName(),
                    author == null ? null : DtoImages.avatar(author.getProfileImageUrl()),
                    t.getTitle(),
                    t.getDescription(),
                    t.getKind(),
                    t.getCreatedAt(),
                    t.getPlaceLabel(),
                    urls,
                    t.getAuthoredAsGroupId(),
                    groupName,
                    groupType
            );
        }
    }

    /**
     * Entity-only conversion. Author profile fields stay null — the
     * caller (typically {@code PostService}) is expected to fold in
     * profile data via {@link #withAuthor(UserInfo)} so the FE doesn't
     * need a separate profiles-batch round trip.
     */
    public static PostDto fromEntity(Post t, Double distanceKm) {
        List<String> keys = t.getImageKeys() == null ? List.of() : t.getImageKeys();
        List<String> urls = keys.stream()
                .map(PublicCdn::toPublicUrl)
                .collect(Collectors.toList());
        return new PostDto(
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
                t.getParentPostId(),
                t.getTags() == null ? Set.of() : t.getTags(),
                keys,
                urls,
                distanceKm,
                t.isSponsored(),
                t.isCrisisRelevant(),
                t.getSponsoredUntil(),
                t.getSponsoredBy(),
                /* authorType */ null,
                /* verifiedState */ null,
                /* publisherScope */ null,
                /* publisherProfileUrl */ null,
                /* serviceAreaLabel */ null,
                /* jurisdictionLabel */ null,
                sponsoredDisclosure(t.isSponsored(), t.getSponsoredBy()),
                t.getKind(),
                t.getPrice(),
                t.isFree(),
                parsePaymentMethods(t.getPaymentMethodsJson()),
                /* viaFollow */ false,
                /* thanksCount */ 0,
                /* viewerThanked */ false,
                /* commentsCount */ 0,
                /* reactionsByEmoji */ Map.of(),
                /* viewerEmojis */ Set.of(),
                /* latestCommentPreview */ null,
                t.getAuthoredAsGroupId(),
                /* authoredAsGroupName */ null,
                /* authoredAsGroupType */ null,
                t.getAssigneeEmail(),
                /* parentPost */ null,
                CommunityExtras.fromEntity(t),
                t.isLiabilityRequired(), t.isReleaseSigned(), t.getReleaseTextHash(), t.getReleaseExceptionReason(),
                t.isNearPowerLines(), t.isElectricalHazard(), t.getWaterLevel(), t.getSafeToEnter(),
                withWorkPhotoUrls(t.getWorkDetails()),
                t.getNeedType(),
                List.of(),
                /* projectRollup — folded by PostService.withProjectRollup */ null,
                /* children      — folded by PostService on the detail path */ null
        );
    }

    public static PostDto fromEntity(Post t) {
        return fromEntity(t, null);
    }

    /**
     * Wire-only enrichment for the work_details bag: when it carries the
     * before/after photo KEY arrays (bare R2 keys — the persisted form, see
     * {@code PostService.updateWorkPhotos}), ship derived {@code *PhotoUrls}
     * siblings alongside so clients render without knowing the CDN host —
     * the same key→URL fold {@code imageKeys → imageUrls} uses. The URL
     * fields are never persisted (the write paths strip them); the bag is
     * copied, not mutated, so the entity's map stays untouched.
     */
    private static Map<String, Object> withWorkPhotoUrls(Map<String, Object> wd) {
        if (wd == null
                || (!wd.containsKey("beforePhotoKeys") && !wd.containsKey("afterPhotoKeys"))) {
            return wd;
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>(wd);
        putPhotoUrls(out, "beforePhotoKeys", "beforePhotoUrls");
        putPhotoUrls(out, "afterPhotoKeys", "afterPhotoUrls");
        return out;
    }

    private static void putPhotoUrls(Map<String, Object> out, String keysField, String urlsField) {
        Object v = out.get(keysField);
        if (v instanceof List<?> list) {
            List<String> urls = list.stream()
                    .filter(o -> o != null)
                    .map(o -> PublicCdn.toPublicUrl(o.toString()))
                    .collect(Collectors.toList());
            out.put(urlsField, urls);
        }
    }

    /**
     * Returns a copy with {@code viaFollow=true}. Used by the community
     * feed merge in {@code PostService.discoverCommunity} when a post is
     * surfaced because the author is followed by the viewer (out-of-radius
     * pull-through).
     */
    public PostDto asFollowSource() {
        return new PostDto(
                id, groupId, requesterEmail,
                requesterFirstName, requesterLastName, requesterProfileImageUrl,
                claimedByGroupId, claimedByEmail, status, priority,
                title, description, latitude, longitude, zipBucket, placeLabel,
                dueAt, createdAt, updatedAt, claimedAt, completedAt,
                parentPostId, tags, imageKeys, imageUrls, distanceKm,
                sponsored, crisisRelevant, sponsoredUntil, sponsoredBy,
                authorType, verifiedState, publisherScope, publisherProfileUrl,
                serviceAreaLabel, jurisdictionLabel, sponsoredDisclosure,
                kind, price, isFree, paymentMethods,
                /* viaFollow */ true,
                thanksCount, viewerThanked, commentsCount,
                reactionsByEmoji, viewerEmojis,
                latestCommentPreview,
                authoredAsGroupId, authoredAsGroupName, authoredAsGroupType,
                assigneeEmail,
                parentPost,
                community,
                liabilityRequired(), releaseSigned(), releaseTextHash(), releaseExceptionReason(),
                nearPowerLines(), electricalHazard(), waterLevel(), safeToEnter(), workDetails(), needType(),
                assignees, projectRollup, children
        );
    }

    /**
     * Returns a copy of this DTO with author profile fields populated
     * from {@code u}. Used by {@code PostService.discoverCommunity}
     * after a batch UserInfo lookup. Profile-image key is converted to
     * the strict DTO avatar URL shape so the FE renders either a public
     * CDN URL or its standard no-avatar fallback.
     */
    public PostDto withAuthor(UserInfo u) {
        if (u == null) return this;
        String avatarUrl = DtoImages.avatar(u.getProfileImageUrl());
        PublisherIdentity identity = publisherIdentity(u);
        return new PostDto(
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
                parentPostId,
                tags,
                imageKeys,
                imageUrls,
                distanceKm,
                sponsored,
                crisisRelevant,
                sponsoredUntil,
                sponsoredBy,
                coalesce(identity.authorType(), authorType),
                coalesce(identity.verifiedState(), verifiedState),
                coalesce(identity.publisherScope(), publisherScope),
                coalesce(identity.publisherProfileUrl(), publisherProfileUrl),
                coalesce(identity.serviceAreaLabel(), serviceAreaLabel),
                coalesce(identity.jurisdictionLabel(), jurisdictionLabel),
                sponsoredDisclosure,
                kind,
                price,
                isFree,
                paymentMethods,
                viaFollow,
                thanksCount,
                viewerThanked,
                commentsCount,
                reactionsByEmoji,
                viewerEmojis,
                latestCommentPreview,
                authoredAsGroupId,
                authoredAsGroupName,
                authoredAsGroupType,
                assigneeEmail,
                parentPost,
                community,
                liabilityRequired(), releaseSigned(), releaseTextHash(), releaseExceptionReason(),
                nearPowerLines(), electricalHazard(), waterLevel(), safeToEnter(), workDetails(), needType(),
                assignees, projectRollup, children
        );
    }

    /**
     * Returns a copy with thank counts + viewer-thanked + comments-count
     * folded in. Used by the listing path so a feed page is one batched
     * query for reaction summary + one for comment counts, rather than
     * N round trips.
     */
    public PostDto withEngagement(int thanksCount, boolean viewerThanked, int commentsCount) {
        return new PostDto(
                id, groupId, requesterEmail,
                requesterFirstName, requesterLastName, requesterProfileImageUrl,
                claimedByGroupId, claimedByEmail, status, priority,
                title, description, latitude, longitude, zipBucket, placeLabel,
                dueAt, createdAt, updatedAt, claimedAt, completedAt,
                parentPostId, tags, imageKeys, imageUrls, distanceKm,
                sponsored, crisisRelevant, sponsoredUntil, sponsoredBy,
                authorType, verifiedState, publisherScope, publisherProfileUrl,
                serviceAreaLabel, jurisdictionLabel, sponsoredDisclosure,
                kind, price, isFree, paymentMethods, viaFollow,
                thanksCount, viewerThanked, commentsCount,
                reactionsByEmoji, viewerEmojis,
                latestCommentPreview,
                authoredAsGroupId, authoredAsGroupName, authoredAsGroupType,
                assigneeEmail,
                parentPost,
                community,
                liabilityRequired(), releaseSigned(), releaseTextHash(), releaseExceptionReason(),
                nearPowerLines(), electricalHazard(), waterLevel(), safeToEnter(), workDetails(), needType(),
                assignees, projectRollup, children
        );
    }

    /**
     * Returns a copy with the per-emoji reaction summary folded in.
     * Chained after {@link #withEngagement} in {@code PostService.withEngagement}
     * so the listing path can populate both the legacy thanksCount /
     * viewerThanked AND the new multi-emoji breakdown in one row pass.
     * Null inputs default to empty so the FE always gets stable shapes.
     */
    public PostDto withReactions(Map<String, Integer> reactionsByEmoji, Set<String> viewerEmojis) {
        Map<String, Integer> safeMap = reactionsByEmoji == null ? Map.of() : reactionsByEmoji;
        Set<String> safeSet = viewerEmojis == null ? Set.of() : viewerEmojis;
        return new PostDto(
                id, groupId, requesterEmail,
                requesterFirstName, requesterLastName, requesterProfileImageUrl,
                claimedByGroupId, claimedByEmail, status, priority,
                title, description, latitude, longitude, zipBucket, placeLabel,
                dueAt, createdAt, updatedAt, claimedAt, completedAt,
                parentPostId, tags, imageKeys, imageUrls, distanceKm,
                sponsored, crisisRelevant, sponsoredUntil, sponsoredBy,
                authorType, verifiedState, publisherScope, publisherProfileUrl,
                serviceAreaLabel, jurisdictionLabel, sponsoredDisclosure,
                kind, price, isFree, paymentMethods, viaFollow,
                thanksCount, viewerThanked, commentsCount,
                safeMap, safeSet,
                latestCommentPreview,
                authoredAsGroupId, authoredAsGroupName, authoredAsGroupType,
                assigneeEmail,
                parentPost,
                community,
                liabilityRequired(), releaseSigned(), releaseTextHash(), releaseExceptionReason(),
                nearPowerLines(), electricalHazard(), waterLevel(), safeToEnter(), workDetails(), needType(),
                assignees, projectRollup, children
        );
    }

    /**
     * Returns a copy with the most-recent comment preview folded in.
     * Chained after {@link #withReactions} in {@code PostService.withEngagement}
     * so the listing path can populate the IG/FB-style preview in the
     * same single row pass. Null preview is valid (no comments).
     */
    public PostDto withLatestComment(CommentPreviewDto preview) {
        return new PostDto(
                id, groupId, requesterEmail,
                requesterFirstName, requesterLastName, requesterProfileImageUrl,
                claimedByGroupId, claimedByEmail, status, priority,
                title, description, latitude, longitude, zipBucket, placeLabel,
                dueAt, createdAt, updatedAt, claimedAt, completedAt,
                parentPostId, tags, imageKeys, imageUrls, distanceKm,
                sponsored, crisisRelevant, sponsoredUntil, sponsoredBy,
                authorType, verifiedState, publisherScope, publisherProfileUrl,
                serviceAreaLabel, jurisdictionLabel, sponsoredDisclosure,
                kind, price, isFree, paymentMethods, viaFollow,
                thanksCount, viewerThanked, commentsCount,
                reactionsByEmoji, viewerEmojis,
                preview,
                authoredAsGroupId, authoredAsGroupName, authoredAsGroupType,
                assigneeEmail,
                parentPost,
                community,
                liabilityRequired(), releaseSigned(), releaseTextHash(), releaseExceptionReason(),
                nearPowerLines(), electricalHazard(), waterLevel(), safeToEnter(), workDetails(), needType(),
                assignees, projectRollup, children
        );
    }

    /**
     * Returns a copy with the authored-as-group identity (name + type)
     * folded in. Called by {@code PostService} after looking up the
     * {@link io.sitprep.sitprepapi.domain.Group} row matching
     * {@link #authoredAsGroupId} so the FE can render the group's
     * emblem + name as the author header without a separate group-
     * profile round-trip.
     *
     * <p>No-op when {@code authoredAsGroupId} is null (the post is
     * authored by an individual — common case). When the group lookup
     * fails (group deleted, etc.), {@code name} + {@code type} stay
     * null and the FE falls back to the individual {@code requester*}
     * fields.</p>
     */
    public PostDto withAuthoredAsGroup(String name, String type) {
        if (authoredAsGroupId == null || authoredAsGroupId.isBlank()) return this;
        String groupAuthorType = authorTypeFromKind(type);
        String groupScope = publisherScopeFromKind(type);
        return new PostDto(
                id, groupId, requesterEmail,
                requesterFirstName, requesterLastName, requesterProfileImageUrl,
                claimedByGroupId, claimedByEmail, status, priority,
                title, description, latitude, longitude, zipBucket, placeLabel,
                dueAt, createdAt, updatedAt, claimedAt, completedAt,
                parentPostId, tags, imageKeys, imageUrls, distanceKm,
                sponsored, crisisRelevant, sponsoredUntil, sponsoredBy,
                coalesce(authorType, groupAuthorType),
                verifiedState,
                coalesce(publisherScope, groupScope),
                publisherProfileUrl,
                serviceAreaLabel,
                jurisdictionLabel,
                sponsoredDisclosure,
                kind, price, isFree, paymentMethods, viaFollow,
                thanksCount, viewerThanked, commentsCount,
                reactionsByEmoji, viewerEmojis,
                latestCommentPreview,
                authoredAsGroupId, name, type,
                assigneeEmail,
                parentPost,
                community,
                liabilityRequired(), releaseSigned(), releaseTextHash(), releaseExceptionReason(),
                nearPowerLines(), electricalHazard(), waterLevel(), safeToEnter(), workDetails(), needType(),
                assignees, projectRollup, children
        );
    }

    /**
     * Returns a copy with a compact parent preview folded in. Used for
     * repost / quote-post rendering.
     */
    public PostDto withParentPost(ParentPostPreview preview) {
        return new PostDto(
                id, groupId, requesterEmail,
                requesterFirstName, requesterLastName, requesterProfileImageUrl,
                claimedByGroupId, claimedByEmail, status, priority,
                title, description, latitude, longitude, zipBucket, placeLabel,
                dueAt, createdAt, updatedAt, claimedAt, completedAt,
                parentPostId, tags, imageKeys, imageUrls, distanceKm,
                sponsored, crisisRelevant, sponsoredUntil, sponsoredBy,
                authorType, verifiedState, publisherScope, publisherProfileUrl,
                serviceAreaLabel, jurisdictionLabel, sponsoredDisclosure,
                kind, price, isFree, paymentMethods, viaFollow,
                thanksCount, viewerThanked, commentsCount,
                reactionsByEmoji, viewerEmojis,
                latestCommentPreview,
                authoredAsGroupId, authoredAsGroupName, authoredAsGroupType,
                assigneeEmail,
                preview,
                community,
                liabilityRequired(), releaseSigned(), releaseTextHash(), releaseExceptionReason(),
                nearPowerLines(), electricalHazard(), waterLevel(), safeToEnter(), workDetails(), needType(),
                assignees, projectRollup, children
        );
    }

    /**
     * Returns a copy with the work-order assignee list folded in (Step 2).
     * Populated by {@code PostService.withAssignees} from {@code task_assignee}
     * + a batch UserInfo lookup. Non-task / unassigned rows get an empty list.
     */
    public PostDto withAssignees(List<AssigneeDto> assignees) {
        return new PostDto(
                id, groupId, requesterEmail,
                requesterFirstName, requesterLastName, requesterProfileImageUrl,
                claimedByGroupId, claimedByEmail, status, priority,
                title, description, latitude, longitude, zipBucket, placeLabel,
                dueAt, createdAt, updatedAt, claimedAt, completedAt,
                parentPostId, tags, imageKeys, imageUrls, distanceKm,
                sponsored, crisisRelevant, sponsoredUntil, sponsoredBy,
                authorType, verifiedState, publisherScope, publisherProfileUrl,
                serviceAreaLabel, jurisdictionLabel, sponsoredDisclosure,
                kind, price, isFree, paymentMethods, viaFollow,
                thanksCount, viewerThanked, commentsCount,
                reactionsByEmoji, viewerEmojis,
                latestCommentPreview,
                authoredAsGroupId, authoredAsGroupName, authoredAsGroupType,
                assigneeEmail,
                parentPost,
                community,
                liabilityRequired(), releaseSigned(), releaseTextHash(), releaseExceptionReason(),
                nearPowerLines(), electricalHazard(), waterLevel(), safeToEnter(), workDetails(), needType(),
                assignees == null ? List.of() : assignees,
                projectRollup, children);
    }

    /**
     * Bundles / projects (V51) — returns a copy with the DERIVED project
     * roll-up folded in. Populated by {@code PostService.withProjectRollup}
     * from a single grouped child query; no-op on the FE side (read-only).
     * Only ever called for {@code kind="project"} rows.
     */
    public PostDto withProjectRollup(ProjectRollup projectRollup) {
        return new PostDto(
                id, groupId, requesterEmail,
                requesterFirstName, requesterLastName, requesterProfileImageUrl,
                claimedByGroupId, claimedByEmail, status, priority,
                title, description, latitude, longitude, zipBucket, placeLabel,
                dueAt, createdAt, updatedAt, claimedAt, completedAt,
                parentPostId, tags, imageKeys, imageUrls, distanceKm,
                sponsored, crisisRelevant, sponsoredUntil, sponsoredBy,
                authorType, verifiedState, publisherScope, publisherProfileUrl,
                serviceAreaLabel, jurisdictionLabel, sponsoredDisclosure,
                kind, price, isFree, paymentMethods, viaFollow,
                thanksCount, viewerThanked, commentsCount,
                reactionsByEmoji, viewerEmojis,
                latestCommentPreview,
                authoredAsGroupId, authoredAsGroupName, authoredAsGroupType,
                assigneeEmail,
                parentPost,
                community,
                liabilityRequired(), releaseSigned(), releaseTextHash(), releaseExceptionReason(),
                nearPowerLines(), electricalHazard(), waterLevel(), safeToEnter(), workDetails(), needType(),
                assignees, projectRollup, children);
    }

    /**
     * Bundles / projects (V51) — returns a copy with the project's child task
     * DTOs folded in. Populated by {@code PostService} ONLY on the
     * project-detail path (the group list omits children and shows the
     * roll-up's childCount instead). An empty list is a valid value: a project
     * with no tasks yet.
     */
    public PostDto withChildren(List<PostDto> children) {
        return new PostDto(
                id, groupId, requesterEmail,
                requesterFirstName, requesterLastName, requesterProfileImageUrl,
                claimedByGroupId, claimedByEmail, status, priority,
                title, description, latitude, longitude, zipBucket, placeLabel,
                dueAt, createdAt, updatedAt, claimedAt, completedAt,
                parentPostId, tags, imageKeys, imageUrls, distanceKm,
                sponsored, crisisRelevant, sponsoredUntil, sponsoredBy,
                authorType, verifiedState, publisherScope, publisherProfileUrl,
                serviceAreaLabel, jurisdictionLabel, sponsoredDisclosure,
                kind, price, isFree, paymentMethods, viaFollow,
                thanksCount, viewerThanked, commentsCount,
                reactionsByEmoji, viewerEmojis,
                latestCommentPreview,
                authoredAsGroupId, authoredAsGroupName, authoredAsGroupType,
                assigneeEmail,
                parentPost,
                community,
                liabilityRequired(), releaseSigned(), releaseTextHash(), releaseExceptionReason(),
                nearPowerLines(), electricalHazard(), waterLevel(), safeToEnter(), workDetails(), needType(),
                assignees, projectRollup, children);
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

    private static PublisherIdentity publisherIdentity(UserInfo u) {
        if (u == null || !u.isVerifiedPublisher()) {
            return new PublisherIdentity(null, null, null, null, null, null);
        }
        String kind = u.getVerifiedPublisherKind();
        String email = u.getUserEmail();
        String verified = u.isVerifiedPublisherEmergencyPostingEnabled()
                || "official-agency".equalsIgnoreCase(String.valueOf(kind))
                ? "official"
                : "verified";
        return new PublisherIdentity(
                authorTypeFromKind(kind),
                verified,
                publisherScopeFromKind(kind),
                email == null || email.isBlank() ? null : "/business/" + email.trim(),
                trim(u.getVerifiedPublisherServiceArea()),
                coalesce(u.getVerifiedPublisherTemporaryEventAddress(),
                        u.getVerifiedPublisherPermanentAddress())
        );
    }

    private static String authorTypeFromKind(String kind) {
        String k = normalizeKind(kind);
        return switch (k) {
            case "business", "commercial" -> "business";
            case "city" -> "city";
            case "county" -> "county";
            case "state" -> "state";
            case "officialagency", "publicsafety", "utility" -> "officialAgency";
            case "organization", "nonprofit", "school", "church", "neighborhood" -> "organization";
            default -> null;
        };
    }

    private static String publisherScopeFromKind(String kind) {
        String k = normalizeKind(kind);
        return switch (k) {
            case "business", "commercial" -> "commercial";
            case "city" -> "municipal";
            case "county" -> "county";
            case "state" -> "state";
            case "officialagency", "publicsafety", "utility" -> "publicSafety";
            case "organization", "nonprofit", "school", "church", "neighborhood" -> "nonprofit";
            default -> null;
        };
    }

    private static String sponsoredDisclosure(boolean sponsored, String sponsoredBy) {
        if (!sponsored) return null;
        String by = trim(sponsoredBy);
        return by == null ? "Sponsored" : "Sponsored by " + by;
    }

    private static String normalizeKind(String value) {
        if (value == null || value.isBlank()) return "";
        return value.trim().replaceAll("[^A-Za-z0-9]", "").toLowerCase();
    }

    private static String coalesce(String preferred, String fallback) {
        String trimmed = trim(preferred);
        return trimmed == null ? trim(fallback) : trimmed;
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private record PublisherIdentity(
            String authorType,
            String verifiedState,
            String publisherScope,
            String publisherProfileUrl,
            String serviceAreaLabel,
            String jurisdictionLabel
    ) {}

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
