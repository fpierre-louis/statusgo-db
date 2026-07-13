package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.util.GeoUtil;
import io.sitprep.sitprepapi.exception.LiabilityNotAcceptedException;
import io.sitprep.sitprepapi.constant.CivicCategory;
import io.sitprep.sitprepapi.constant.CivicStatus;
import io.sitprep.sitprepapi.constant.OfficialTier;
import io.sitprep.sitprepapi.constant.PostKind;
import io.sitprep.sitprepapi.domain.AskBookmark;
import io.sitprep.sitprepapi.domain.Follow;
import io.sitprep.sitprepapi.domain.PostConfirm;
import io.sitprep.sitprepapi.repo.AskBookmarkRepo;
import io.sitprep.sitprepapi.repo.PostConfirmRepo;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Post.PostStatus;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.repo.FollowRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.util.PublicCdn;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Post / request-for-help service. Three scopes (group / community-personal /
 * group-claimed-community) share one {@link Post} entity. Scope is implicit
 * from groupId / claimedByGroupId nullability — see entity Javadoc.
 *
 * <p>This service does the create + lifecycle transitions (claim, complete,
 * cancel, reopen) plus the queries each surface needs:</p>
 * <ul>
 *   <li>Group feed — by groupId</li>
 *   <li>Community feed — by lat/lng/radius (Haversine in Java; zip-bucket pre-filter via repo)</li>
 *   <li>My tasks — by requester or claimer email</li>
 * </ul>
 */
@Service
public class PostService {

    private static final Logger log = LoggerFactory.getLogger(PostService.class);

    /** Mean Earth radius in km — matches CommunityDiscoverService. */
    private static final double EARTH_RADIUS_KM = 6371.0088;

    /**
     * Authorized post kinds — see Post.kind Javadoc + the spec
     * {@code docs/MARKETPLACE_AND_FEED_CALM.md} "Feed: post types
     * beyond Asks". Lowercased.
     *
     * <p>Pre-2026-05-03 this was a free-form set of strings. The
     * canonical source of truth is now {@link PostKind} — adding a
     * new kind means adding an enum value, and the validator below
     * picks it up automatically. The set is kept here as a thin
     * alias so call sites that already imported it don't churn.</p>
     */
    private static final Set<String> AUTHORIZED_KINDS = PostKind.ALLOWED_WIRE_VALUES;

    private final PostRepo taskRepo;
    private final UserInfoRepo userInfoRepo;
    private final NominatimGeocodeService geocode;
    private final WebSocketMessageSender ws;
    private final AlertModeService alertModeService;
    private final FollowRepo followRepo;
    private final BlockService blockService;
    private final PostReactionService reactionService;
    private final PostCommentService commentService;
    private final StorageService storage;
    private final GroupRepo groupRepo;
    private final PublisherPublishAuditService publisherPublishAuditService;
    private final AgencyAuthorizationService agencyAuthorizationService;
    private final PostConfirmRepo postConfirmRepo;
    private final AskBookmarkRepo askBookmarkRepo;
    private final WorkOrderQuotaService workOrderQuotaService;

    public record PostSharePreview(
            String title,
            String description,
            String imageUrl
    ) {}

    public PostService(PostRepo taskRepo, UserInfoRepo userInfoRepo,
                       NominatimGeocodeService geocode,
                       WebSocketMessageSender ws,
                       AlertModeService alertModeService,
                       FollowRepo followRepo,
                       BlockService blockService,
                       PostReactionService reactionService,
                       PostCommentService commentService,
                       StorageService storage,
                       GroupRepo groupRepo,
                       PublisherPublishAuditService publisherPublishAuditService,
                       AgencyAuthorizationService agencyAuthorizationService,
                       PostConfirmRepo postConfirmRepo,
                       AskBookmarkRepo askBookmarkRepo,
                       WorkOrderQuotaService workOrderQuotaService) {
        this.taskRepo = taskRepo;
        this.userInfoRepo = userInfoRepo;
        this.geocode = geocode;
        this.ws = ws;
        this.alertModeService = alertModeService;
        this.followRepo = followRepo;
        this.blockService = blockService;
        this.reactionService = reactionService;
        this.commentService = commentService;
        this.storage = storage;
        this.groupRepo = groupRepo;
        this.publisherPublishAuditService = publisherPublishAuditService;
        this.agencyAuthorizationService = agencyAuthorizationService;
        this.postConfirmRepo = postConfirmRepo;
        this.askBookmarkRepo = askBookmarkRepo;
        this.workOrderQuotaService = workOrderQuotaService;
    }

    /**
     * Apply mode-aware sponsored suppression per
     * {@code docs/SPONSORED_AND_ALERT_MODE.md} "Suppression rules in
     * detail":
     * <ul>
     *   <li>{@code calm} — show everything (organic + sponsored).</li>
     *   <li>{@code attention} / {@code alert} — drop sponsored UNLESS
     *       {@code crisisRelevant=true} (those still show, with FE-side
     *       "Verified service nearby" lane labeling).</li>
     *   <li>{@code crisis} — drop ALL sponsored regardless of
     *       crisisRelevant. Verified-publisher organic content gets
     *       the focus instead.</li>
     * </ul>
     *
     * <p>Run after the radius filter + distance sort but before the
     * cap-50 trim, so suppressed sponsored doesn't take a slot a real
     * organic ask could occupy.</p>
     */
    private List<PostDto> applySponsoredSuppression(List<PostDto> tasks, String mode) {
        if (mode == null || AlertModeService.CALM.equalsIgnoreCase(mode)) return tasks;
        boolean isCrisis = AlertModeService.CRISIS.equalsIgnoreCase(mode);
        List<PostDto> out = new ArrayList<>(tasks.size());
        for (PostDto t : tasks) {
            if (!t.sponsored()) {
                out.add(t);
                continue;
            }
            if (isCrisis) continue; // hide all sponsored
            if (t.crisisRelevant()) out.add(t); // keep crisis-relevant in attention/alert
            // else: drop sponsored, non-crisis-relevant
        }
        return out;
    }

    /**
     * Batch-fold author profile fields into a list of PostDto. Honors
     * the codebase principle "backend shapes the data, frontend just
     * displays" — feed surfaces render the standard post anatomy
     * (avatar + name + 3-dot menu) without fanning out a separate
     * /userinfo/profiles/batch round trip per page-load.
     *
     * <p>One DB call total via {@code findByUserEmailIn}. Tasks whose
     * requesterEmail can't be resolved (deleted account, anon) flow
     * through unchanged with null author fields — the FE handles that
     * by falling back to email-as-name + initials.</p>
     */
    private List<PostDto> withAuthors(List<PostDto> dtos) {
        if (dtos == null || dtos.isEmpty()) return dtos;
        List<String> emails = dtos.stream()
                .map(PostDto::requesterEmail)
                .filter(Objects::nonNull)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .distinct()
                .collect(Collectors.toList());
        if (emails.isEmpty()) return dtos;
        Map<String, UserInfo> byEmail = userInfoRepo.findByUserEmailIn(emails).stream()
                .filter(u -> u.getUserEmail() != null)
                .collect(Collectors.toMap(
                        u -> u.getUserEmail().toLowerCase(Locale.ROOT),
                        Function.identity(),
                        (a, b) -> a
                ));
        return dtos.stream()
                .map(d -> {
                    if (d.requesterEmail() == null) return d;
                    UserInfo u = byEmail.get(d.requesterEmail().toLowerCase(Locale.ROOT));
                    return (u == null) ? d : d.withAuthor(u);
                })
                .collect(Collectors.toList());
    }

    /**
     * Batch-fold the authored-as-group identity (groupName + groupType)
     * into a list of PostDto. Mirrors {@link #withAuthors} — one DB
     * call total via {@code groupRepo.findAllById}, then map-lookup
     * per row. Posts with null authoredAsGroupId flow through
     * unchanged (the common case).
     *
     * <p>When the matching group can't be resolved (deleted, etc.),
     * the row keeps its authoredAsGroupId but name/type stay null;
     * the FE falls back to the individual requester fields.</p>
     */
    private List<PostDto> withAuthoredAsGroups(List<PostDto> dtos) {
        if (dtos == null || dtos.isEmpty()) return dtos;
        List<String> ids = dtos.stream()
                .map(PostDto::authoredAsGroupId)
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .distinct()
                .collect(Collectors.toList());
        if (ids.isEmpty()) return dtos;
        Map<String, Group> byId = new HashMap<>();
        for (Group g : groupRepo.findAllById(ids)) {
            if (g != null && g.getGroupId() != null) byId.put(g.getGroupId(), g);
        }
        return dtos.stream()
                .map(d -> {
                    if (d.authoredAsGroupId() == null) return d;
                    Group g = byId.get(d.authoredAsGroupId());
                    if (g == null) return d;
                    return d.withAuthoredAsGroup(g.getGroupName(), g.getGroupType());
                })
                .collect(Collectors.toList());
    }

    /**
     * Batch-fold parent post previews for repost / quote-post cards.
     * Parent previews are intentionally compact and scope-aware: a
     * community post cannot expose a group-only parent unless the child
     * lives in the same group scope.
     */
    private List<PostDto> withParentPosts(List<PostDto> dtos) {
        if (dtos == null || dtos.isEmpty()) return dtos;
        List<Long> parentIds = dtos.stream()
                .map(PostDto::parentPostId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (parentIds.isEmpty()) return dtos;

        Map<Long, Post> parentsById = new HashMap<>();
        for (Post p : taskRepo.findAllById(parentIds)) {
            if (p != null && p.getId() != null) parentsById.put(p.getId(), p);
        }
        if (parentsById.isEmpty()) return dtos;

        List<String> parentEmails = parentsById.values().stream()
                .map(Post::getRequesterEmail)
                .filter(Objects::nonNull)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        Map<String, UserInfo> authorByEmail = parentEmails.isEmpty()
                ? Map.of()
                : userInfoRepo.findByUserEmailIn(parentEmails).stream()
                    .filter(u -> u.getUserEmail() != null)
                    .collect(Collectors.toMap(
                            u -> u.getUserEmail().toLowerCase(Locale.ROOT),
                            Function.identity(),
                            (a, b) -> a
                    ));

        List<String> authoredGroupIds = parentsById.values().stream()
                .map(Post::getAuthoredAsGroupId)
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
        Map<String, Group> groupById = new HashMap<>();
        for (Group g : groupRepo.findAllById(authoredGroupIds)) {
            if (g != null && g.getGroupId() != null) groupById.put(g.getGroupId(), g);
        }

        return dtos.stream()
                .map(d -> {
                    Long parentId = d.parentPostId();
                    if (parentId == null) return d;
                    Post parent = parentsById.get(parentId);
                    if (parent == null) return d;
                    boolean parentIsGroupScoped = parent.getGroupId() != null && !parent.getGroupId().isBlank();
                    boolean sameGroupScope = Objects.equals(parent.getGroupId(), d.groupId());
                    if (parentIsGroupScoped && !sameGroupScope) return d;

                    UserInfo author = parent.getRequesterEmail() == null
                            ? null
                            : authorByEmail.get(parent.getRequesterEmail().toLowerCase(Locale.ROOT));
                    Group authoredGroup = parent.getAuthoredAsGroupId() == null
                            ? null
                            : groupById.get(parent.getAuthoredAsGroupId());
                    return d.withParentPost(PostDto.ParentPostPreview.fromEntity(
                            parent,
                            author,
                            authoredGroup == null ? null : authoredGroup.getGroupName(),
                            authoredGroup == null ? null : authoredGroup.getGroupType()
                    ));
                })
                .collect(Collectors.toList());
    }

    /**
     * Batch-fold engagement counts (heart "Thank" count, viewer-thanked
     * flag, and — once Phase 2 ships — comment count) onto a list of
     * TaskDtos. Single batched query for the reaction summary so a
     * 50-row feed page is one extra DB round trip total, not 50.
     *
     * <p>{@code viewerEmail} null means an unauthenticated read (or a
     * read where viewer identity isn't available); {@code viewerThanked}
     * defaults to false everywhere. Counts always populate regardless.</p>
     *
     * <p>Phase 2 (2026-05-04) wires comment counts via {@code PostCommentService.loadCountsByPostIds}
     * — one batched count query per page-load alongside the reaction summary.
     * Tasks with no comments are absent from the count map; we default to 0
     * for missing keys so the FE renders the comment icon without a count.</p>
     */
    private List<PostDto> withEngagement(List<PostDto> dtos, String viewerEmail) {
        if (dtos == null || dtos.isEmpty()) return dtos;
        List<Long> ids = dtos.stream()
                .map(PostDto::id)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) return dtos;
        // Two batched fetches against the reaction table — one for the
        // legacy thank-only summary (drives the heart-fill state),
        // another for the per-emoji summary that powers the new
        // multi-emoji cluster display under each card. Both reuse the
        // same `findByPostIdIn` query path internally so this is one
        // DB hit per fetch, not per post.
        PostReactionService.ThankSummary thankSummary =
                reactionService.loadThankSummary(ids, viewerEmail);
        PostReactionService.ReactionSummary reactionSummary =
                reactionService.loadReactionSummary(ids, viewerEmail);
        Map<Long, Integer> commentCounts = commentService.loadCountsByPostIds(ids);
        // Latest-comment preview per post (IG/FB-style "teased reply" on
        // the feed card). One batched query + one batched author-profile
        // lookup, so a 50-post page costs +2 round trips total — same
        // amortization model as reactions/counts above.
        Map<Long, io.sitprep.sitprepapi.dto.CommentPreviewDto> latestPreviews =
                commentService.loadLatestPreviewsByPostIds(ids);

        // Community-redesign folds: first-class confirms + saved viewer state
        // + tagged-agency display name. Each is one batched query.
        Map<Long, Long> confirmCounts = postConfirmRepo.findByPostIdIn(ids).stream()
                .collect(Collectors.groupingBy(PostConfirm::getPostId, Collectors.counting()));
        Set<Long> viewerConfirmed = (viewerEmail == null || viewerEmail.isBlank())
                ? Set.of()
                : new HashSet<>(postConfirmRepo.findPostIdsWhereViewerConfirmed(ids, viewerEmail));
        Set<String> savedKeys = (viewerEmail == null || viewerEmail.isBlank())
                ? Set.of()
                : askBookmarkRepo.findUserBookmarksIn(viewerEmail, "post",
                        ids.stream().map(String::valueOf).toList())
                    .stream().map(AskBookmark::getTargetKey).collect(Collectors.toSet());
        List<String> agencyIds = dtos.stream()
                .map(d -> d.community() == null ? null : d.community().taggedAgency())
                .filter(Objects::nonNull)
                .map(PostDto.CommunityExtras.TaggedAgency::id)
                .filter(Objects::nonNull).distinct().toList();
        Map<String, Group> agencyById = new HashMap<>();
        if (!agencyIds.isEmpty()) {
            for (Group g : groupRepo.findAllById(agencyIds)) {
                if (g != null && g.getGroupId() != null) agencyById.put(g.getGroupId(), g);
            }
        }

        return dtos.stream()
                .map(d -> {
                    if (d.id() == null) return d;
                    PostDto out = d.withEngagement(
                            thankSummary.countFor(d.id()),
                            thankSummary.viewerThankedTask(d.id()),
                            commentCounts.getOrDefault(d.id(), 0)
                    ).withReactions(
                            reactionSummary.countsFor(d.id()),
                            reactionSummary.viewerEmojisFor(d.id())
                    ).withLatestComment(
                            latestPreviews.get(d.id())
                    );
                    PostDto.CommunityExtras ce = out.community();
                    if (ce != null) {
                        ce = ce.withConfirms(
                                confirmCounts.getOrDefault(d.id(), 0L).intValue(),
                                viewerConfirmed.contains(d.id())
                        ).withSaved(savedKeys.contains(String.valueOf(d.id())));
                        if (ce.taggedAgency() != null) {
                            Group g = agencyById.get(ce.taggedAgency().id());
                            if (g != null) ce = ce.withAgencyIdentity(g.getGroupName(), true);
                        }
                        out = out.withCommunity(ce);
                    }
                    return out;
                })
                .collect(Collectors.toList());
    }

    // ---------------------------------------------------------------------
    // Create
    // ---------------------------------------------------------------------

    @Transactional
    public PostDto create(Post incoming, String requesterEmail) {
        if (requesterEmail == null || requesterEmail.isBlank()) {
            throw new IllegalArgumentException("requesterEmail required");
        }

        Post t = new Post();
        t.setRequesterEmail(requesterEmail.trim().toLowerCase());
        t.setGroupId(incoming.getGroupId()); // null = community/personal scope

        // authoredAsGroup attribution — when set, the requester must be
        // an admin (or owner) of the target group. Misconfigured clients
        // that send a groupId the user can't speak for get a 400 rather
        // than a silently-stripped attribution, since silent strip would
        // make a non-admin appear to author as the group from their own
        // client and confuse the audit trail.
        String aagId = incoming.getAuthoredAsGroupId();
        if (aagId != null && !aagId.isBlank()) {
            Group g = groupRepo.findByGroupId(aagId.trim())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "authoredAsGroupId references an unknown group"));
            String me = requesterEmail.trim().toLowerCase();
            boolean isAdmin = g.getAdminEmails() != null && g.getAdminEmails().stream()
                    .anyMatch(e -> e != null && e.equalsIgnoreCase(me));
            boolean isOwner = g.getOwnerEmail() != null && g.getOwnerEmail().equalsIgnoreCase(me);
            if (!isAdmin && !isOwner) {
                throw new IllegalArgumentException(
                        "Only admins can post on behalf of a group");
            }
            t.setAuthoredAsGroupId(g.getGroupId());
        }
        // Title goes through per-kind validation below; description first
        // so the validation can require one when title is absent.
        t.setDescription(incoming.getDescription());
        t.setStatus(incoming.getStatus() != null ? incoming.getStatus() : PostStatus.OPEN);
        t.setPriority(incoming.getPriority() != null ? incoming.getPriority() : Post.PostPriority.MEDIUM);
        // Unified work-order fields (V43). Additive; inert on non-task kinds.
        // liabilityRequired flags a work order that must capture a signed
        // release before it can move to IN_PROGRESS / VERIFICATION_PENDING /
        // CLOSED / DONE (enforced by assertLiabilitySatisfied + the
        // ck_task_liability_gate DB constraint). Triage fields carry the
        // legacy disaster-relief hazard context.
        t.setLiabilityRequired(incoming.isLiabilityRequired());
        t.setNearPowerLines(incoming.isNearPowerLines());
        t.setElectricalHazard(incoming.isElectricalHazard());
        t.setWaterLevel(blankToNull(incoming.getWaterLevel()));
        t.setSafeToEnter(incoming.getSafeToEnter());
        // Dynamic, need-type-specific intake bag (V47). Bound straight off the
        // @RequestBody Post via Jackson and persisted as jsonb; null/inert on
        // personal tasks and every non-work-order kind.
        t.setWorkDetails(incoming.getWorkDetails());
        // Denormalize the need-type discriminator onto its own indexed column,
        // derived from the bag (the wizard carries needType inside work_details)
        // or an explicit root needType if the client sends one — so the column
        // stays authoritative for dispatch queries without trusting two sources.
        t.setNeedType(resolveNeedType(incoming));
        // Delegation (Step 4): assign to a member at create when the wizard set
        // one. The dedicated /assign endpoint remains the reassignment path.
        t.setAssigneeEmail(blankToNull(incoming.getAssigneeEmail()));
        // Phase 5 — server-side life-safety derivation. ZERO-TRUST: interrogates
        // the authoritative work_details flags, escalating priority regardless of
        // the client-submitted value, and stamps the derived safety snapshots.
        deriveLifeSafety(t);
        GeoUtil.requireValidLatLng(incoming.getLatitude(), incoming.getLongitude());
        t.setLatitude(incoming.getLatitude());
        t.setLongitude(incoming.getLongitude());
        t.setDueAt(incoming.getDueAt());
        t.setParentPostId(incoming.getParentPostId());
        if (t.getParentPostId() != null && !taskRepo.existsById(t.getParentPostId())) {
            throw new IllegalArgumentException("parentPostId references an unknown post");
        }
        if (incoming.getTags() != null) t.getTags().addAll(incoming.getTags());
        if (incoming.getImageKeys() != null) {
            // BE-side cap to match the composer's MAX_IMAGES (currently
            // 5, bumped from 3 on 2026-05-11). A bypassed FE could
            // otherwise post arbitrarily many keys and bloat the row
            // (image_key column rows are @ElementCollection => one DB
            // row per key). Clean 400 rather than letting a giant
            // payload through.
            if (incoming.getImageKeys().size() > 5) {
                throw new IllegalArgumentException("A post can have at most 5 images.");
            }
            t.getImageKeys().addAll(incoming.getImageKeys());
        }

        // Kind validation — defaults to "ask" so legacy callers (the
        // existing CommunityTaskComposer) work unchanged. Non-default
        // kinds get the spec's authorized-set check before persist.
        String kind = incoming.getKind();
        if (kind == null || kind.isBlank()) {
            t.setKind("ask");
        } else {
            String k = kind.trim().toLowerCase();
            if (!AUTHORIZED_KINDS.contains(k)) {
                throw new IllegalArgumentException(
                        "kind must be one of " + AUTHORIZED_KINDS + ", got " + kind);
            }
            t.setKind(k);
        }

        // Per-kind title rules. Title is required for kinds where the
        // composer exposes a title field separate from the body
        // ({@link PostKind#requiresTitle}); for body-only kinds (post,
        // tip) the description is the post and title stays null. Pre-
        // 2026-05-04 the FE composer synthesized a title from the
        // description's first line for these kinds, which then rendered
        // bolded above the same description in the feed card — the
        // visible duplicate that prompted the cleanup.
        PostKind kindEnum = PostKind.fromWire(t.getKind());
        boolean titlePresent = incoming.getTitle() != null && !incoming.getTitle().isBlank();
        if (kindEnum != null && kindEnum.requiresTitle()) {
            if (!titlePresent) {
                throw new IllegalArgumentException("title required for kind=" + t.getKind());
            }
            t.setTitle(incoming.getTitle().trim());
        } else {
            // post / tip: description is the body; ignore any title that
            // a legacy client (or a stale cached composer build) might
            // still send. Description must be present unless this is a
            // bare repost whose parent card is the visible content.
            boolean hasParentPost = t.getParentPostId() != null;
            if (!hasParentPost && (incoming.getDescription() == null || incoming.getDescription().isBlank())) {
                throw new IllegalArgumentException(
                        "description required for kind=" + t.getKind());
            }
            t.setTitle(null);
            t.setDescription(incoming.getDescription() == null ? null : incoming.getDescription().trim());
        }

        // Marketplace fields. price + isFree are mutually exclusive
        // and only meaningful when kind="marketplace"; silently drop
        // them on other kinds rather than throw, since they may be
        // present in legacy payloads.
        if ("marketplace".equals(t.getKind())) {
            if (incoming.getPrice() != null && incoming.isFree()) {
                throw new IllegalArgumentException("Listing can be priced or free, not both");
            }
            t.setPrice(incoming.getPrice());
            t.setFree(incoming.isFree());
            // Payment-method handles. Sized so an attacker can't bloat
            // a row with a megabyte of garbage. Validates as parseable
            // JSON before persist; null/empty/invalid → no handles.
            String pm = incoming.getPaymentMethodsJson();
            if (pm != null && !pm.isBlank()) {
                if (pm.length() > 4096) {
                    throw new IllegalArgumentException("paymentMethodsJson too large");
                }
                try {
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(pm);
                } catch (Exception je) {
                    throw new IllegalArgumentException("paymentMethodsJson is not valid JSON");
                }
                t.setPaymentMethodsJson(pm);
            }
        }

        // Community-redesign per-type fields (official / news / civic-report).
        // @RequestBody binds the raw columns; this authorizes + defaults them.
        applyCommunityTypeFields(t, incoming, requesterEmail);

        // Civic-report → work-order linkage (Phase 5 Slice H). When an agency
        // admin issues a work order off a civic report, link it back and
        // acknowledge the still-new report.
        applyWorkOrderSourceLink(t, incoming, requesterEmail);

        // Reverse-geocode to populate zipBucket (community-feed pre-filter)
        // and placeLabel (Nextdoor-style "{neighborhood} · {time}" subtitle
        // on feed cards). Safe to skip on group-scope tasks since the
        // group itself provides location context.
        if (t.getGroupId() == null && t.getLatitude() != null && t.getLongitude() != null) {
            try {
                NominatimGeocodeService.Place p = geocode.reverse(t.getLatitude(), t.getLongitude());
                if (p != null) {
                    t.setZipBucket(p.zipBucket());
                    t.setPlaceLabel(p.shortLabel());
                }
            } catch (Exception e) {
                log.debug("Post geo enrichment failed: {}", e.getMessage());
            }
        }

        // Metered monetization (Phase 2) — a group-scoped work order counts
        // against the owning group's monthly plan allowance. Personal tasks
        // (groupId null) and civic-report kinds are never metered. A group at
        // its cap gets a 402 here, before the row is persisted.
        if (t.getGroupId() != null && "task".equals(t.getKind())) {
            groupRepo.findByGroupId(t.getGroupId())
                    .ifPresent(workOrderQuotaService::assertQuota);
        }

        publisherPublishAuditService.requirePublisherPostAllowed(
                t.getAuthoredAsGroupId(), t.getRequesterEmail(), requesterEmail, false);
        Post saved = taskRepo.save(t);
        PostDto dto = PostDto.fromEntity(saved);
        // Fold in authored-as-group identity (name + type) so the
        // newly-created post returns with the group emblem already
        // populated — no second round-trip needed on the FE to render
        // the post card with the right author header.
        dto = withParentPosts(withAuthoredAsGroups(withAuthors(List.of(dto)))).get(0);
        publisherPublishAuditService.recordCommunityPost(saved, requesterEmail);
        broadcastAfterCommit(dto);
        return dto;
    }

    /**
     * Authorize + default the community-redesign per-type fields on create.
     * Official + news are gated to verified publishers; civic reports are
     * stamped REPORTED and must tag an existing agency group.
     */
    private void applyCommunityTypeFields(Post t, Post incoming, String actorEmail) {
        String kind = t.getKind();
        if ("official".equals(kind)) {
            String agencyId = blankToNull(t.getAuthoredAsGroupId());
            if (agencyId != null) {
                Group agency = groupRepo.findByGroupId(agencyId)
                        .orElseThrow(() -> new IllegalArgumentException("authoredAsGroupId references an unknown agency"));
                agencyAuthorizationService.requireAgencyPostingAllowed(agency, actorEmail);
                if (t.getLatitude() == null && agency.getJurisdictionLat() != null) {
                    t.setLatitude(agency.getJurisdictionLat());
                }
                if (t.getLongitude() == null && agency.getJurisdictionLng() != null) {
                    t.setLongitude(agency.getJurisdictionLng());
                }
            } else {
                UserInfo author = userInfoRepo.findByUserEmail(actorEmail.trim().toLowerCase()).orElse(null);
                if (author == null || !author.isVerifiedPublisherEmergencyPostingEnabled()) {
                    throw new IllegalArgumentException("Only verified emergency publishers can post official alerts");
                }
            }
            String tier = incoming.getOfficialTier() == null ? null : incoming.getOfficialTier().trim().toLowerCase();
            if (!OfficialTier.isValid(tier)) {
                throw new IllegalArgumentException("officialTier must be one of " + OfficialTier.ALLOWED_WIRE_VALUES);
            }
            t.setOfficialTier(tier);
            if (OfficialTier.EMERGENCY.wire().equals(tier) || incoming.getPinnedUntil() != null) {
                t.setPinnedAt(Instant.now());
                t.setPinnedBy(actorEmail.trim().toLowerCase());
                t.setPinnedUntil(incoming.getPinnedUntil());
            }
        } else if ("news".equals(kind)) {
            UserInfo author = userInfoRepo.findByUserEmail(actorEmail.trim().toLowerCase()).orElse(null);
            if (author == null || !author.isVerifiedPublisher()) {
                throw new IllegalArgumentException("Only verified publishers can post news");
            }
            t.setSourceName(blankToNull(incoming.getSourceName()));
            t.setSourceUrl(blankToNull(incoming.getSourceUrl()));
            t.setReadMinutes(incoming.getReadMinutes());
        } else if ("civic-report".equals(kind)) {
            String cat = incoming.getCivicCategory() == null ? null : incoming.getCivicCategory().trim().toLowerCase();
            if (!CivicCategory.isValid(cat)) {
                throw new IllegalArgumentException("civicCategory must be one of " + CivicCategory.ALLOWED_WIRE_VALUES);
            }
            String agencyId = blankToNull(incoming.getTaggedAgencyGroupId());
            if (agencyId == null) {
                throw new IllegalArgumentException("civic-report requires a taggedAgencyGroupId");
            }
            Group agency = groupRepo.findByGroupId(agencyId)
                    .orElseThrow(() -> new IllegalArgumentException("taggedAgencyGroupId references an unknown group"));
            // Must be a VERIFIED agency (a verified publisher linked to this group)
            // — keeps the feed-card "verified" badge honest and stops a crafted
            // request from tagging an arbitrary group. Mirrors listAgencies.
            if (userInfoRepo.findFirstByVerifiedPublisherGroupIdIgnoreCase(agency.getGroupId()).isEmpty()) {
                throw new IllegalArgumentException("taggedAgencyGroupId must reference a verified agency");
            }
            t.setCivicCategory(cat);
            t.setTaggedAgencyGroupId(agency.getGroupId());
            t.setCivicStatus(CivicStatus.REPORTED.wire());
        }
    }

    /**
     * Link an agency work order back to the civic report that prompted it
     * (Phase 5 Slice H). Authorization is checked against the report's
     * tagged agency — NOT the work order's groupId — because create does
     * not (today) verify group-admin for kind="task". Only that agency's
     * owner/admins may link, which also keeps the auto-acknowledge honest.
     * Creating the work order acknowledges a still-"reported" card; later
     * lifecycle states (scheduled/resolved) are never regressed.
     */
    private void applyWorkOrderSourceLink(Post t, Post incoming, String actorEmail) {
        Long sourceId = incoming.getSourcePostId();
        if (sourceId == null) return;
        Post source = taskRepo.findById(sourceId).orElse(null);
        if (source == null
                || source.getCivicStatus() == null
                || source.getTaggedAgencyGroupId() == null) {
            return; // only civic-report sources are linkable
        }
        Group agency = groupRepo.findByGroupId(source.getTaggedAgencyGroupId()).orElse(null);
        String me = actorEmail.trim().toLowerCase();
        boolean authorized = agency != null && (
                (agency.getOwnerEmail() != null && agency.getOwnerEmail().equalsIgnoreCase(me))
                        || (agency.getAdminEmails() != null && agency.getAdminEmails().stream()
                        .anyMatch(x -> x != null && x.equalsIgnoreCase(me))));
        if (!authorized) return; // never link a stranger's task to a report

        t.setSourcePostId(sourceId);
        if (CivicStatus.fromWire(source.getCivicStatus()) == CivicStatus.REPORTED) {
            source.setCivicStatus(CivicStatus.ACKNOWLEDGED.wire());
            source.setCivicAckedAt(Instant.now());
            if (source.getAgencyNote() == null || source.getAgencyNote().isBlank()) {
                source.setAgencyNote("Work order created");
            }
            taskRepo.save(source);
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * Resolve the need-type discriminator for the {@code need_type} column.
     * Prefers an explicit root {@code needType} if the client sent one, else
     * derives it from {@code work_details.needType} (the wizard carries the
     * discriminator inside the bag). Null when neither is present (personal
     * tasks / non-work-order kinds).
     */
    private static String resolveNeedType(Post incoming) {
        if (incoming == null) return null;
        String root = blankToNull(incoming.getNeedType());
        if (root != null) return root;
        Map<String, Object> wd = incoming.getWorkDetails();
        if (wd != null) {
            Object nt = wd.get("needType");
            if (nt != null && !nt.toString().isBlank()) return nt.toString().trim();
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Phase 5 — server-side life-safety derivation (zero-trust escalation).
    // -----------------------------------------------------------------------

    /** Universal life-safety flags in work_details that force an emergency
     *  escalation regardless of the client-submitted priority. */
    private static final List<String> LIFE_SAFETY_FLAGS = List.of(
            "occupantTrapped", "medicalPowerDependence", "gasLeakSuspected",
            "downedPowerLine", "structurallyUnsafe", "hazmatSpillPresent",
            "carbonMonoxideRisk");

    /**
     * Interrogate the authoritative {@code work_details} bag and:
     * <ol>
     *   <li><b>ZERO-TRUST escalation:</b> if ANY universal life-safety flag is
     *       true, force the Post priority to {@code URGENT} (the highest state) —
     *       the server has final say, overriding whatever the client sent;</li>
     *   <li><b>Derived snapshots:</b> compute + stamp {@code siteSafeToEnter},
     *       {@code habitability}, and {@code dispatcherPriority} back into the
     *       bag. These are never trusted from the client; the server is their
     *       sole author.</li>
     * </ol>
     * No-op on non-work-order rows (needType null) so plain community posts are
     * untouched.
     */
    private void deriveLifeSafety(Post t) {
        Map<String, Object> wd = t.getWorkDetails();
        boolean anyLifeSafety = wd != null
                && LIFE_SAFETY_FLAGS.stream().anyMatch(k -> boolTrue(wd.get(k)));

        // (1) Zero-trust escalation — the server's final say on priority.
        if (anyLifeSafety) {
            t.setPriority(Post.PostPriority.URGENT);
        }

        // (2) Derived snapshots — only for actual work orders (needType present).
        if (t.getNeedType() == null) return;

        Map<String, Object> next = (wd == null) ? new LinkedHashMap<>() : new LinkedHashMap<>(wd);
        next.put("siteSafeToEnter", deriveSiteSafeToEnter(next, t.getSafeToEnter(), t.isElectricalHazard()));
        next.put("habitability", deriveHabitability(next, t.isElectricalHazard()));
        next.put("dispatcherPriority", anyLifeSafety ? "emergency" : dispatcherFromPriority(t.getPriority()));
        t.setWorkDetails(next);
    }

    /** "Do I send a crew, and with what precautions?" — the derived go/no-go. */
    private static String deriveSiteSafeToEnter(Map<String, Object> wd, Boolean safeToEnterCol, boolean electricalHazardCol) {
        if (boolTrue(wd.get("gasLeakSuspected")) || boolTrue(wd.get("downedPowerLine"))
                || boolTrue(wd.get("structurallyUnsafe")) || boolTrue(wd.get("hazmatSpillPresent"))
                || boolTrue(wd.get("carbonMonoxideRisk")) || boolTrue(wd.get("ceilingCollapseRisk"))
                || "red".equals(str(wd.get("inspectionPlacardColor")))
                || Boolean.FALSE.equals(safeToEnterCol)) {
            return "unsafeDoNotEnter";
        }
        if (electricalHazardCol || boolTrue(wd.get("sewageContamination")) || boolTrue(wd.get("moldPresent"))
                || boolTrue(wd.get("spillReachedWaterway")) || boolTrue(wd.get("standingWaterPresent"))
                || "yellow".equals(str(wd.get("inspectionPlacardColor")))) {
            return "cautionPpeRequired";
        }
        if (Boolean.TRUE.equals(safeToEnterCol)) return "safe";
        return "needsAssessment";
    }

    /** "Can occupants safely remain?" — the derived habitability class. */
    private static String deriveHabitability(Map<String, Object> wd, boolean electricalHazardCol) {
        if (boolTrue(wd.get("structurallyUnsafe")) || boolTrue(wd.get("ceilingCollapseRisk"))
                || "red".equals(str(wd.get("inspectionPlacardColor")))
                || boolTrue(wd.get("gasLeakSuspected")) || boolTrue(wd.get("hazmatSpillPresent"))
                || boolTrue(wd.get("carbonMonoxideRisk"))) {
            return "uninhabitable";
        }
        if (boolTrue(wd.get("downedPowerLine")) || electricalHazardCol
                || boolTrue(wd.get("sewageContamination")) || boolTrue(wd.get("standingWaterPresent"))
                || boolTrue(wd.get("moldPresent")) || "yellow".equals(str(wd.get("inspectionPlacardColor")))) {
            return "habitableWithCaution";
        }
        return "unknown";
    }

    /** Map the (post-escalation) Post priority to the dispatch-queue label. */
    private static String dispatcherFromPriority(Post.PostPriority p) {
        return switch (p == null ? Post.PostPriority.MEDIUM : p) {
            case URGENT, HIGH -> "urgent";
            case MEDIUM -> "routine";
            case LOW -> "scheduled";
        };
    }

    /** Lenient truthiness for a jsonb value (Boolean true or the string "true"). */
    private static boolean boolTrue(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return "true".equalsIgnoreCase(s.trim());
        return false;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    // ---------------------------------------------------------------------
    // Community-redesign engagement + civic lifecycle
    // ---------------------------------------------------------------------

    /** Result of a confirm toggle — the fresh count + the viewer's new state. */
    public record ConfirmResult(int confirmsCount, boolean viewerConfirmed) {}

    @Transactional
    public ConfirmResult addConfirm(Long postId, String email) {
        if (!taskRepo.existsById(postId)) throw new IllegalArgumentException("unknown post");
        String e = email.trim().toLowerCase();
        if (postConfirmRepo.findByPostIdAndUserEmailIgnoreCase(postId, e).isEmpty()) {
            try {
                PostConfirm c = new PostConfirm();
                c.setPostId(postId);
                c.setUserEmail(e);
                // saveAndFlush so a concurrent-insert unique violation surfaces
                // HERE (caught below) rather than poisoning the commit with a 500.
                postConfirmRepo.saveAndFlush(c);
            } catch (org.springframework.dao.DataIntegrityViolationException dup) {
                // A racing confirm for the same (post,user) already landed — idempotent success.
            }
        }
        return new ConfirmResult((int) postConfirmRepo.countByPostId(postId), true);
    }

    @Transactional
    public ConfirmResult removeConfirm(Long postId, String email) {
        postConfirmRepo.deleteByPostAndUser(postId, email.trim().toLowerCase());
        return new ConfirmResult((int) postConfirmRepo.countByPostId(postId), false);
    }

    /** Toggle a feed-post bookmark, reusing the generic AskBookmark table (target_type="post"). */
    @Transactional
    public boolean toggleSave(Long postId, String email, boolean save) {
        if (!taskRepo.existsById(postId)) throw new IllegalArgumentException("unknown post");
        String e = email.trim().toLowerCase();
        String key = String.valueOf(postId);
        if (save) {
            if (askBookmarkRepo.findByUserEmailAndTargetTypeAndTargetKey(e, "post", key).isEmpty()) {
                AskBookmark b = new AskBookmark();
                b.setUserEmail(e);
                b.setTargetType("post");
                b.setTargetKey(key);
                askBookmarkRepo.save(b);
            }
            return true;
        }
        askBookmarkRepo.deleteByUserEmailAndTargetTypeAndTargetKey(e, "post", key);
        return false;
    }

    /** Agency-only forward advance of a civic-report's status (+ optional note). */
    @Transactional
    public PostDto updateCivicStatus(Long postId, String newStatus, String note, String actorEmail) {
        Post t = taskRepo.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("unknown post"));
        if (t.getCivicStatus() == null) {
            throw new IllegalArgumentException("not a civic report");
        }
        CivicStatus from = CivicStatus.fromWire(t.getCivicStatus());
        CivicStatus to = CivicStatus.fromWire(newStatus == null ? null : newStatus.trim().toLowerCase());
        if (to == null) throw new IllegalArgumentException("invalid civic status");
        if (!CivicStatus.canAdvanceTo(from, to)) {
            throw new IllegalStateException("cannot move civic status " + from + " -> " + to);
        }
        Group agency = groupRepo.findByGroupId(t.getTaggedAgencyGroupId())
                .orElseThrow(() -> new IllegalArgumentException("tagged agency missing"));
        String me = actorEmail.trim().toLowerCase();
        boolean owner = agency.getOwnerEmail() != null && agency.getOwnerEmail().equalsIgnoreCase(me);
        boolean admin = agency.getAdminEmails() != null && agency.getAdminEmails().stream()
                .anyMatch(x -> x != null && x.equalsIgnoreCase(me));
        if (!owner && !admin) {
            throw new IllegalArgumentException("Only the tagged agency's admins can update civic status");
        }
        t.setCivicStatus(to.wire());
        if (note != null && !note.isBlank()) {
            String n = note.trim();
            t.setAgencyNote(n.length() > 280 ? n.substring(0, 280) : n); // column cap
        }
        Instant now = Instant.now();
        if (to == CivicStatus.ACKNOWLEDGED) t.setCivicAckedAt(now);
        else if (to == CivicStatus.SCHEDULED) t.setScheduledFor(now);
        else if (to == CivicStatus.RESOLVED) t.setResolvedAt(now);
        Post saved = taskRepo.save(t);
        PostDto dto = withEngagement(
                withParentPosts(withAuthoredAsGroups(withAuthors(List.of(PostDto.fromEntity(saved))))), me).get(0);
        broadcastAfterCommit(dto);
        return dto;
    }

    // ---------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<PostDto> listByGroup(String groupId, PostStatus status, String viewerEmail) {
        if (groupId == null || groupId.isBlank()) return List.of();
        List<Post> rows = (status == null)
                ? taskRepo.findByGroupIdOrderByCreatedAtDesc(groupId)
                : taskRepo.findByGroupIdAndStatusOrderByCreatedAtDesc(groupId, status);
        List<PostDto> dtos = rows.stream().map(PostDto::fromEntity).collect(Collectors.toList());
        return withEngagement(withParentPosts(withAuthoredAsGroups(withAuthors(dtos))), viewerEmail);
    }

    /** Back-compat overload — viewerThanked stays false for callers that don't pass identity. */
    @Transactional(readOnly = true)
    public List<PostDto> listByGroup(String groupId, PostStatus status) {
        return listByGroup(groupId, status, null);
    }

    @Transactional(readOnly = true)
    public List<PostDto> listRequestedBy(String email) {
        if (email == null || email.isBlank()) return List.of();
        // Requester is the viewer for this surface — viewerThanked uses the
        // same email so a user's own thanks render correctly on their list.
        List<PostDto> dtos = taskRepo.findByRequesterEmailIgnoreCaseOrderByCreatedAtDesc(email).stream()
                .map(PostDto::fromEntity).collect(Collectors.toList());
        return withEngagement(withParentPosts(withAuthoredAsGroups(withAuthors(dtos))), email);
    }

    /**
     * Public-profile feed slice: community-scope posts authored by the
     * profile owner, newest first. Uses the same enrichment pipeline as
     * {@link #discoverCommunity(double, double, double, Set, String, int)}
     * so profile cards and community cards receive the same DTO anatomy.
     */
    @Transactional(readOnly = true)
    public List<PostDto> listPublicProfilePosts(String requesterEmail, String viewerEmail, int limit) {
        if (requesterEmail == null || requesterEmail.isBlank()) return List.of();
        int cap = limit <= 0 ? 10 : Math.min(limit, 50);
        List<PostDto> dtos = taskRepo.findByRequesterEmailIgnoreCaseOrderByCreatedAtDesc(requesterEmail).stream()
                .filter(t -> t.getGroupId() == null)
                .limit(cap)
                .map(PostDto::fromEntity)
                .collect(Collectors.toList());
        return withEngagement(withParentPosts(withAuthoredAsGroups(withAuthors(dtos))), viewerEmail);
    }

    @Transactional(readOnly = true)
    public List<PostDto> listClaimedBy(String email) {
        if (email == null || email.isBlank()) return List.of();
        // Claimer is the viewer for this surface (same reasoning as listRequestedBy).
        List<PostDto> dtos = taskRepo.findByClaimedByEmailIgnoreCaseOrderByCreatedAtDesc(email).stream()
                .map(PostDto::fromEntity).collect(Collectors.toList());
        return withEngagement(withParentPosts(withAuthoredAsGroups(withAuthors(dtos))), email);
    }

    /**
     * Tasks assigned to the user (group push flow — {@code assigneeEmail}).
     * Backs {@code GET /api/me/posts?role=assignee}, the third arm of the
     * unified "my work" union (requester ∪ claimer ∪ assignee).
     */
    public List<PostDto> listAssignedTo(String email) {
        if (email == null || email.isBlank()) return List.of();
        List<PostDto> dtos = taskRepo.findByAssigneeEmailIgnoreCaseOrderByCreatedAtDesc(email).stream()
                .map(PostDto::fromEntity).collect(Collectors.toList());
        return withEngagement(withParentPosts(withAuthoredAsGroups(withAuthors(dtos))), email);
    }

    /**
     * Community feed with follow-merge — per
     * {@code docs/PROFILE_AND_FOLLOW.md} build-order step 4. Returns:
     * <ul>
     *   <li>Open community-scope tasks within {@code radiusKm} (existing behavior)</li>
     *   <li>Geo-less tasks (community-wide, distanceKm=null)</li>
     *   <li>Out-of-radius tasks authored by emails {@code viewerEmail}
     *       follows, tagged {@code viaFollow=true} + computed distance</li>
     * </ul>
     *
     * <p>Sort order: in-radius posts by proximity (existing), then
     * geo-less posts, then follow-source posts by recency. The
     * follow-source tail is intentionally less prominent than radius
     * matches — the spec calls for "visible but not dominating".</p>
     *
     * <p>{@code viewerEmail} null skips the follow merge entirely
     * (back-compat for callers that don't carry viewer identity).</p>
     */
    @Transactional(readOnly = true)
    public List<PostDto> discoverCommunity(double lat, double lng, double radiusKm,
                                           Set<PostStatus> statuses, String viewerEmail) {
        return discoverCommunity(lat, lng, radiusKm, statuses, viewerEmail, 0, 50);
    }

    /**
     * Paged variant — tier ranking (official &gt; civic &gt; news &gt;
     * neighbor &gt; sponsored, layered on the relevance score) + offset/limit
     * windowing. {@code limit} is capped at 50 per page.
     */
    @Transactional(readOnly = true)
    public List<PostDto> discoverCommunity(double lat, double lng, double radiusKm,
                                           Set<PostStatus> statuses, String viewerEmail,
                                           int offset, int limit) {
        Set<PostStatus> wanted = (statuses == null || statuses.isEmpty())
                ? EnumSet.of(PostStatus.OPEN, PostStatus.CLAIMED) : statuses;

        // Resolve the viewer's followed-emails set ONCE per request. Lower-
        // cased to match the Follow column convention. Empty when the viewer
        // is null OR follows nobody — both cases short-circuit the merge.
        Set<String> followedEmails;
        if (viewerEmail == null || viewerEmail.isBlank()) {
            followedEmails = Set.of();
        } else {
            followedEmails = followRepo.findByFollowerEmail(viewerEmail.trim().toLowerCase(Locale.ROOT))
                    .stream()
                    .map(Follow::getFollowedEmail)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        }

        // Block-aware filter — drop posts authored by anyone in either
        // direction of a block relationship with the viewer
        // (PROFILE_AND_FOLLOW step 5: "Block trumps everything"). Empty
        // for anonymous viewers, which is fine — block-suppression is
        // viewer-specific by definition.
        Set<String> blockSet = (viewerEmail == null || viewerEmail.isBlank())
                ? Set.of()
                : blockService.getBlockSet(viewerEmail);

        // No bucket pre-filter for now (we don't know the viewer's zip ahead
        // of the request). Service-layer Haversine + status filter is fine
        // at SitPrep scale; add bucket prefilter when row counts grow.
        List<Post> candidates = taskRepo.findCommunityCandidates(wanted, null);

        // Walk candidates ONCE; bucket each into within-radius vs follow-
        // source-out-of-radius vs drop. Geo-less rows are community-wide
        // by construction — they belong in every viewer's feed (existing
        // behavior, preserved here verbatim).
        List<PostDto> within = new ArrayList<>();
        List<PostDto> followTail = new ArrayList<>();
        for (Post t : candidates) {
            // Block filter — symmetric, applied before any geo math so
            // we don't waste cycles on rows the viewer will never see.
            String author = t.getRequesterEmail();
            String authorNormalized = author == null ? null : author.toLowerCase(Locale.ROOT);
            if (authorNormalized != null && blockSet.contains(authorNormalized)) continue;

            if (t.getLatitude() == null || t.getLongitude() == null) {
                within.add(PostDto.fromEntity(t, null));
                continue;
            }
            double d = haversineKm(lat, lng, t.getLatitude(), t.getLongitude());
            if (d <= radiusKm) {
                within.add(PostDto.fromEntity(t, roundKm(d)));
                continue;
            }
            // Out-of-radius — only include if author is followed.
            if (authorNormalized != null && followedEmails.contains(authorNormalized)) {
                followTail.add(PostDto.fromEntity(t, roundKm(d)).asFollowSource());
            }
        }
        // Hybrid relevance sort (locked 2026-05-09): proximity is still
        // the primary signal, but engagement + recency also count so a
        // fresh urgent post 1.5mi away outranks a quiet 0.4mi post from
        // last week. Pure distance-sort buried fresh posts behind stale
        // popular ones; this scoring keeps proximity dominant while
        // letting age/engagement break ties within the same broad radius
        // band.
        //
        // Score (higher = better):
        //   -DISTANCE_WEIGHT * km
        //   + RECENCY_WEIGHT * exp(-ageHours / HALF_LIFE_HOURS)
        //   + ENGAGEMENT_WEIGHT * log10(thanks + comments + 1)
        //
        // Tunable constants kept as final fields rather than @ConfigurationProperties
        // so they're discoverable inline next to the algorithm. Promote to
        // application.properties when we actually want runtime tuning.
        //
        // null distance still sorts last (community-wide rows belong after
        // all geo-tagged ones) — Double.MAX_VALUE as the distance penalty
        // makes the score so negative they fall to the end naturally.
        // Tier first (official > civic > news > neighbor > sponsored),
        // then the relevance score within a tier — so official/crisis
        // content rises above organic without losing proximity ranking.
        within.sort((a, b) -> {
            int ta = communityTier(a), tb = communityTier(b);
            if (ta != tb) return Integer.compare(ta, tb);
            return Double.compare(communityScore(b), communityScore(a));
        });

        // Follow-source tail by recency — most-recent follow post first.
        // Null createdAt sorts last so legacy rows don't dominate.
        followTail.sort((a, b) -> {
            Instant ai = a.createdAt();
            Instant bi = b.createdAt();
            if (ai == null && bi == null) return 0;
            if (ai == null) return 1;
            if (bi == null) return -1;
            return bi.compareTo(ai);
        });

        List<PostDto> merged = new ArrayList<>(within.size() + followTail.size());
        merged.addAll(within);
        merged.addAll(followTail);

        // Alert pin (locked 2026-05-10) — when there's a fresh
        // alert-update post in the merged set (within radius OR a
        // follow-source), pin the most recent one to position 0 so
        // active emergencies surface above relevance-ranked organic
        // content. Capped at 1 to avoid stacking multiple alerts that
        // would push organic content too far down. Stale alerts (>24h
        // without an update) don't qualify — we trust the post author
        // to repost or update if the situation is still active.
        Instant pinCutoff = Instant.now().minusSeconds(24L * 60 * 60);
        Optional<PostDto> pinned = merged.stream()
                .filter(d -> "alert-update".equals(d.kind()))
                .filter(d -> d.createdAt() != null && d.createdAt().isAfter(pinCutoff))
                .max(Comparator.comparing(PostDto::createdAt));
        if (pinned.isPresent()) {
            PostDto raw = pinned.get();
            // Flag it pinned so the FE renders the "Pinned by your area"
            // strip — this post is what replaced the old top alert band.
            final PostDto pin = raw.community() != null
                    ? raw.withCommunity(raw.community().withPinned(true))
                    : raw;
            merged.removeIf(d -> Objects.equals(d.id(), pin.id()));
            // Pin the fresh alert-update BELOW all government/agency official
            // content (tiers 0–2: emergency / advisory / notice) so it leads
            // the ORGANIC content without inverting the "government always
            // surfaces above community" rule. Previously pinned to absolute
            // position 0, which could put a neighbor-authored alert-update
            // above a tier-0 emergency official.
            int insertAt = 0;
            while (insertAt < merged.size() && communityTier(merged.get(insertAt)) <= 2) {
                insertAt++;
            }
            merged.add(insertAt, pin);
        }

        // Apply mode-aware sponsored suppression BEFORE the cap-50 trim
        // so a hidden sponsored row doesn't take a slot a real organic
        // ask could occupy. Mode lookup is cheap (single indexed
        // findById) and doesn't recompute — the cron tick keeps the
        // cell's row current, the FE's request just reads the latest.
        String cellMode;
        try {
            cellMode = alertModeService.getForLatLng(lat, lng).getState();
        } catch (Exception e) {
            // If mode lookup fails, default to calm so we don't
            // accidentally over-suppress on a misbehaving Nominatim or
            // DB blip.
            log.debug("PostService: mode lookup failed for ({}, {}): {}", lat, lng, e.getMessage());
            cellMode = AlertModeService.CALM;
        }
        List<PostDto> filtered = applySponsoredSuppression(merged, cellMode);

        // Kind balance (locked 2026-05-10) — no single post kind takes
        // more than 4 of the top-10 visible slots. Without this a busy
        // marketplace day could push every ask off the first screen,
        // and a quiet news day could make the feed feel like a
        // bulletin of alerts. Items demoted from the top window keep
        // their relative score order in the tail, so users still see
        // them — just past position 9.
        List<PostDto> balanced = applyKindBalance(filtered);

        // Offset/limit window (cursor pagination). limit capped at 50/page.
        int from = Math.max(0, offset);
        int size = (limit <= 0) ? 50 : Math.min(limit, 50);
        List<PostDto> capped = from >= balanced.size()
                ? List.of()
                : balanced.subList(from, Math.min(balanced.size(), from + size));
        return withEngagement(withParentPosts(withAuthoredAsGroups(withAuthors(capped))), viewerEmail);
    }

    /** Feed-ranking tier: 0 emergency → 6 sponsored. Lower ranks higher. */
    private int communityTier(PostDto d) {
        PostDto.CommunityExtras c = d.community();
        String type = (c == null || c.feedItemType() == null) ? "neighbor" : c.feedItemType();
        switch (type) {
            case "official":
                String tier = c.officialTier();
                if ("emergency".equals(tier)) return 0;
                if ("advisory".equals(tier)) return 1;
                return 2;
            case "civic_report": return 3;
            case "news": return 4;
            case "sponsored": return 6;
            default: return 5;
        }
    }

    // ---------------------------------------------------------------------
    // Agencies (civic tag targets) + conditions bar
    // ---------------------------------------------------------------------

    public record AgencyDto(String id, String name, String kind, boolean verified, String jurisdictionLabel) {}

    /**
     * Verified-agency tag targets for civic reports. Sourced from verified
     * publishers linked to a group; best-effort zip match against the
     * publisher's service-area label (returns all when zip is blank).
     */
    @Transactional(readOnly = true)
    public List<AgencyDto> listAgencies(String zip) {
        String z = (zip == null || zip.isBlank()) ? null : zip.trim().toLowerCase();
        List<UserInfo> pubs = userInfoRepo.findByVerifiedPublisherTrue();
        List<String> gids = pubs.stream()
                .map(UserInfo::getVerifiedPublisherGroupId)
                .filter(g -> g != null && !g.isBlank())
                .distinct().toList();
        Map<String, Group> groups = new HashMap<>();
        for (Group g : groupRepo.findAllById(gids)) {
            if (g != null && g.getGroupId() != null) groups.put(g.getGroupId(), g);
        }
        Map<String, AgencyDto> byId = new LinkedHashMap<>();
        for (UserInfo p : pubs) {
            String gid = p.getVerifiedPublisherGroupId();
            if (gid == null || gid.isBlank()) continue;
            Group g = groups.get(gid);
            if (g == null) continue;
            String area = p.getVerifiedPublisherServiceArea();
            if (z != null && (area == null || !area.toLowerCase().contains(z))) continue;
            byId.putIfAbsent(g.getGroupId(),
                    new AgencyDto(g.getGroupId(), g.getGroupName(), p.getVerifiedPublisherKind(), true, area));
        }
        return new ArrayList<>(byId.values());
    }

    public record LocalAgencyDto(String groupId, String name, String logoImageUrl, String jurisdictionType) {}

    /**
     * The verified agency (if any) whose claimed jurisdiction includes the
     * viewer's current cached zip — powers the in-jurisdiction community
     * co-sign (Phase 5 Slice E). Null when the viewer has no cached zip or
     * isn't standing in any agency's jurisdiction.
     */
    @Transactional(readOnly = true)
    public LocalAgencyDto localAgencyForViewer(String email) {
        if (email == null || email.isBlank()) return null;
        UserInfo u = userInfoRepo.findByUserEmailIgnoreCase(email.trim()).orElse(null);
        String zip = u == null ? null : u.getLastKnownZip();
        if (zip == null || zip.isBlank()) return null;
        List<Group> matches = groupRepo.findByJurisdictionZip(zip.trim());
        if (matches == null || matches.isEmpty()) return null;
        Group g = matches.get(0);
        return new LocalAgencyDto(g.getGroupId(), g.getGroupName(), g.getLogoImageUrl(), g.getJurisdictionType());
    }

    public record Condition(String label, Object value, String unit, String status) {}
    public record ConditionsDto(Condition temp, Condition wind, Condition air, Condition power, boolean mocked) {}

    /**
     * Conditions bar (temp / wind / air / power). v1 returns static meters:
     * live NWS-current + AirNow values are fetched FE-side via emergencyApis
     * per the contract, and power has no outage source yet (always "On").
     * The endpoint exists so the FE can switch to a server proxy later
     * without a contract change. {@code mocked=true} signals placeholder data.
     */
    @Transactional(readOnly = true)
    public ConditionsDto getConditions(double lat, double lng) {
        return new ConditionsDto(
                new Condition("Temp", null, "°", "muted"),
                new Condition("Wind", null, "mph", "muted"),
                new Condition("Air", null, "AQI", "muted"),
                new Condition("Power", "On", null, "green"),
                true);
    }

    // ---------------------------------------------------------------------
    // Lifecycle: claim, complete, cancel, reopen, patch, delete
    // ---------------------------------------------------------------------

    /**
     * A group leader claims a task on behalf of their group. The task must
     * currently be unclaimed and OPEN. Caller email must be admin/owner of
     * the claimer group — checked at the resource layer; this service trusts
     * the caller has been authorized.
     *
     * <p>The OPEN-and-unclaimed precondition is enforced by the conditional
     * UPDATE in {@link PostRepo#transitionClaim} — two leaders racing to
     * claim the same task have exactly one UPDATE match; the loser hits
     * the {@code rows == 0} branch and gets the conflict.</p>
     */
    @Transactional
    public PostDto claim(Long postId, String claimerGroupId, String claimerEmail) {
        // Verify existence up front so a missing id is a 400, not a 409.
        mustExist(postId);
        String email = claimerEmail == null ? null : claimerEmail.trim().toLowerCase();
        int rows = taskRepo.transitionClaim(
                postId, PostStatus.OPEN, PostStatus.CLAIMED,
                claimerGroupId, email, Instant.now());
        if (rows == 0) {
            throw new IllegalStateException("Task was claimed by another group");
        }
        return refetchAndBroadcast(postId);
    }

    /**
     * Group admin assigns the task to a member (push assignment). Pass a
     * null/blank {@code assigneeEmail} to clear the assignment. Caller
     * authorization (admin/owner of the task's group) is checked at the
     * resource layer. Assignment is orthogonal to the claim/status
     * lifecycle — an OPEN task can be assigned, and the assignee then
     * works it through the normal in-progress / complete flow.
     *
     * <p>The UPDATE's WHERE rejects DONE / CANCELLED rows atomically —
     * a concurrent complete/cancel beats this assign at the DB level.</p>
     */
    @Transactional
    public PostDto assign(Long postId, String assigneeEmail, String assignedByEmail) {
        mustExist(postId);
        String assignee = (assigneeEmail == null || assigneeEmail.isBlank())
                ? null : assigneeEmail.trim().toLowerCase();
        String assignedBy = (assignee == null) ? null
                : (assignedByEmail == null ? null : assignedByEmail.trim().toLowerCase());
        Instant assignedAt = (assignee == null) ? null : Instant.now();
        int rows = taskRepo.transitionAssign(postId, assignee, assignedBy, assignedAt,
                EnumSet.of(PostStatus.DONE, PostStatus.CANCELLED));
        if (rows == 0) {
            throw new IllegalStateException("Cannot assign a closed task");
        }
        return refetchAndBroadcast(postId);
    }

    /**
     * Mark in-progress — the claimer (community pull flow) or the
     * assignee (group push flow) has started work. An assigned task is
     * worked straight from OPEN; a claimed task moves from CLAIMED.
     */
    @Transactional
    public PostDto markInProgress(Long postId) {
        Post t = mustExist(postId);
        assertLiabilitySatisfied(t, PostStatus.IN_PROGRESS);
        int rows = taskRepo.transitionToInProgress(postId,
                EnumSet.of(PostStatus.OPEN, PostStatus.CLAIMED),
                PostStatus.IN_PROGRESS);
        if (rows == 0) {
            throw new IllegalStateException(
                    "Post must be open or claimed before marking in-progress");
        }
        return refetchAndBroadcast(postId);
    }

    /** Claimer or assignee marks complete. */
    @Transactional
    public PostDto complete(Long postId) {
        Post t = mustExist(postId);
        assertLiabilitySatisfied(t, PostStatus.DONE);
        int rows = taskRepo.transitionComplete(postId, PostStatus.DONE, Instant.now(),
                EnumSet.of(PostStatus.DONE, PostStatus.CANCELLED));
        if (rows == 0) {
            throw new IllegalStateException("Post is already closed");
        }
        return refetchAndBroadcast(postId);
    }

    /** Requester cancels. Frees claimedBy state in case it was claimed. */
    @Transactional
    public PostDto cancel(Long postId) {
        mustExist(postId);
        int rows = taskRepo.transitionCancel(postId, PostStatus.CANCELLED, PostStatus.DONE);
        if (rows == 0) {
            throw new IllegalStateException("Cannot cancel a completed task");
        }
        return refetchAndBroadcast(postId);
    }

    /** Reopen a cancelled task — clears claimer state. */
    @Transactional
    public PostDto reopen(Long postId) {
        mustExist(postId);
        int rows = taskRepo.transitionReopen(postId, PostStatus.CANCELLED, PostStatus.OPEN);
        if (rows == 0) {
            throw new IllegalStateException("Only cancelled tasks can be reopened");
        }
        return refetchAndBroadcast(postId);
    }

    // -----------------------------------------------------------------
    // Liability gate — Phase 2. Application-layer twin of the
    // ck_task_liability_gate DB CHECK (V43). A liability-required work
    // order may not advance into an operational/terminal state until a
    // release is captured (signed, or a documented signing exception).
    // -----------------------------------------------------------------

    /** The states a liability-gated task must not enter unsatisfied. */
    private static final EnumSet<PostStatus> LIABILITY_GATED_STATES = EnumSet.of(
            PostStatus.IN_PROGRESS, PostStatus.VERIFICATION_PENDING,
            PostStatus.CLOSED, PostStatus.DONE);

    /**
     * Assert the liability gate for a target transition. No-op unless the task
     * both {@code liabilityRequired} and is moving into a gated state; then it
     * requires {@code releaseSigned == true} OR a non-blank
     * {@code releaseExceptionReason}.
     *
     * @throws LiabilityNotAcceptedException (→ 409) when the gate is unmet
     */
    private void assertLiabilitySatisfied(Post t, PostStatus target) {
        if (t == null || !t.isLiabilityRequired()) return;
        if (!LIABILITY_GATED_STATES.contains(target)) return;
        boolean satisfied = t.isReleaseSigned()
                || (t.getReleaseExceptionReason() != null && !t.getReleaseExceptionReason().isBlank());
        if (!satisfied) {
            throw new LiabilityNotAcceptedException(
                    "This work order requires a signed liability waiver before it can move to "
                    + target + ". Capture the release (or a documented signing exception) first.");
        }
    }

    /**
     * Capture the liability release for a work order (POST /api/posts/{id}/release).
     * Idempotent — re-submitting the same payload yields the same persisted
     * state. Caller authorization is enforced at the resource layer.
     *
     * @param releaseSigned          true when the requester signed/accepted
     * @param releaseTextHash        SHA-256 of the exact waiver copy shown (optional)
     * @param releaseExceptionReason required when {@code releaseSigned} is false —
     *                               the documented reason no signature was captured
     * @throws IllegalArgumentException (→ 400) when unsigned with no reason
     */
    @Transactional
    public PostDto acceptRelease(Long postId, boolean releaseSigned,
                                 String releaseTextHash, String releaseExceptionReason) {
        Post t = mustExist(postId);
        String reason = blankToNull(releaseExceptionReason);
        if (!releaseSigned && reason == null) {
            throw new IllegalArgumentException(
                    "A release exception reason is required when the waiver is not signed.");
        }
        t.setReleaseSigned(releaseSigned);
        t.setReleaseTextHash(blankToNull(releaseTextHash));
        t.setReleaseExceptionReason(reason);
        Post saved = taskRepo.save(t);
        return PostDto.fromEntity(saved);
    }

    /**
     * Author-only partial update — title, description, priority, dueAt,
     * tags, imageKeys, latitude/longitude. Lifecycle fields (status,
     * claimer*, claimedAt, completedAt) flow through dedicated methods.
     */
    @Transactional
    public PostDto patch(Long postId, Post patch) {
        Post t = mustExist(postId);
        if (patch.getTitle() != null) t.setTitle(patch.getTitle());
        if (patch.getDescription() != null) t.setDescription(patch.getDescription());
        if (patch.getPriority() != null) t.setPriority(patch.getPriority());
        if (patch.getDueAt() != null) t.setDueAt(patch.getDueAt());
        if (patch.getTags() != null) {
            t.getTags().clear();
            t.getTags().addAll(patch.getTags());
        }
        // Image-key diff: free R2 objects whose keys the editor removed.
        // Done BEFORE the entity rewrite so we capture the going-away
        // keys; the actual storage call rides afterCommit (below) so a
        // rolled-back patch never destroys the user's photos. Best-
        // effort per-key — one bad key won't strand the rest.
        List<String> removedKeys = List.of();
        if (patch.getImageKeys() != null) {
            Set<String> incoming = new HashSet<>(patch.getImageKeys());
            List<String> existing = t.getImageKeys() == null ? List.of() : t.getImageKeys();
            removedKeys = existing.stream()
                    .filter(k -> k != null && !k.isBlank() && !incoming.contains(k))
                    .collect(Collectors.toList());
            t.getImageKeys().clear();
            t.getImageKeys().addAll(patch.getImageKeys());
        }
        if (patch.getLatitude() != null) t.setLatitude(patch.getLatitude());
        if (patch.getLongitude() != null) t.setLongitude(patch.getLongitude());
        // Work-order triage bag (V47): replace-if-present, matching this method's
        // per-field patch semantics. When the bag is replaced, re-derive the
        // denormalized need_type column so it can't drift from the bag.
        if (patch.getWorkDetails() != null) {
            t.setWorkDetails(patch.getWorkDetails());
            t.setNeedType(resolveNeedType(patch));
        } else if (patch.getNeedType() != null) {
            t.setNeedType(blankToNull(patch.getNeedType()));
        }
        // Re-derive on update — a newly-patched life-safety flag must re-escalate,
        // and zero-trust holds even if the client lowered priority in this patch.
        deriveLifeSafety(t);
        Post saved = taskRepo.save(t);
        // Enrich the same way every other mutation path does
        // (refetchAndBroadcast / createPost). A bare fromEntity here shipped a
        // null author (requesterFirstName/lastName/profileImageUrl/authorType
        // all null) in both the HTTP response AND the STOMP frame, so editing a
        // post blanked its author + verified badge in the live feed until the
        // next full refetch. Mirror the canonical fold.
        PostDto dto = withParentPosts(withAuthoredAsGroups(withAuthors(List.of(PostDto.fromEntity(saved))))).get(0);
        broadcastAfterCommit(dto);
        if (!removedKeys.isEmpty()) {
            final List<String> toFree = removedKeys;
            final Long id = saved.getId();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    deleteR2ObjectsBestEffort(toFree, "post-patch " + id);
                }
            });
        }
        return dto;
    }

    /**
     * Self-serve promote — author flags their own marketplace listing
     * as sponsored for {@code days} (default 7). Free for v1: SitPrep
     * doesn't process payments, so this is a "claim my own placement"
     * toggle, not a billed action. Future: gate behind a payment flow
     * + admin approval if/when monetization wants real revenue from it.
     *
     * <p>Constraints (service-layer enforced):</p>
     * <ul>
     *   <li>Caller must be the post's author (resource layer pre-checks
     *       this, but the service double-checks for safety).</li>
     *   <li>Post kind must be {@code marketplace} — promotion only makes
     *       sense for marketplace listings; asks/tips don't compete for
     *       a sponsored slot.</li>
     *   <li>Post must currently be OPEN — promoting a sold/cancelled
     *       listing wastes the slot.</li>
     *   <li>{@code days} clamped to [1, 30] so a fat-finger doesn't
     *       grant a year of free placement.</li>
     * </ul>
     *
     * <p>{@code crisisRelevant} stays whatever the post had — promotion
     * doesn't auto-flag a listing as crisis-relevant; that's an admin
     * decision via the admin verify endpoint.</p>
     */
    @Transactional
    public PostDto promote(Long postId, String actorEmail, int days) {
        Post t = mustExist(postId);
        if (t.getRequesterEmail() == null
                || !t.getRequesterEmail().equalsIgnoreCase(actorEmail)) {
            throw new SecurityException("Only the post author can promote it");
        }
        if (!"marketplace".equals(t.getKind())) {
            throw new IllegalStateException(
                    "Only marketplace listings can be promoted (kind=" + t.getKind() + ")");
        }
        if (t.getStatus() != Post.PostStatus.OPEN) {
            throw new IllegalStateException(
                    "Only open listings can be promoted (status=" + t.getStatus() + ")");
        }
        publisherPublishAuditService.requirePublisherPostAllowed(
                t.getAuthoredAsGroupId(), t.getRequesterEmail(), actorEmail, true);
        int clampedDays = Math.max(1, Math.min(30, days));
        t.setSponsored(true);
        t.setSponsoredUntil(Instant.now().plus(java.time.Duration.ofDays(clampedDays)));
        // sponsoredBy = the author themselves for self-serve. Admin-flagged
        // sponsorships use the admin's identifier; self-serve uses the
        // author's email so we can audit who claimed which slot.
        t.setSponsoredBy(actorEmail.trim().toLowerCase());
        publisherPublishAuditService.recordSponsoredPost(t, actorEmail);
        return saveAndBroadcast(t);
    }

    /**
     * Author-only — clear sponsored flag early. Useful when a listing
     * sells out before the sponsored window closes; reduces wasted slot
     * usage in the feed for other listings.
     */
    @Transactional
    public PostDto unpromote(Long postId, String actorEmail) {
        Post t = mustExist(postId);
        if (t.getRequesterEmail() == null
                || !t.getRequesterEmail().equalsIgnoreCase(actorEmail)) {
            throw new SecurityException("Only the post author can unpromote it");
        }
        t.setSponsored(false);
        t.setSponsoredUntil(null);
        // Keep sponsoredBy for audit (it's the historical fact that the
        // listing WAS promoted at some point).
        return saveAndBroadcast(t);
    }

    @Transactional
    public void delete(Long postId) {
        Post t = taskRepo.findById(postId).orElse(null);
        if (t == null) return;
        String groupId = t.getGroupId();
        String zipBucket = t.getZipBucket();
        Long id = t.getId();
        // Snapshot image keys BEFORE the row vanishes so we can free
        // their R2 objects afterCommit (orphaning bytes in Cloudflare
        // when a post is deleted was a recurring storage leak before
        // 2026-05-11 — every deleted ask/marketplace listing left its
        // photos behind paying for storage forever).
        List<String> imageKeys = t.getImageKeys() == null
                ? List.of()
                : new ArrayList<>(t.getImageKeys());
        taskRepo.delete(t);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    ws.sendPostDeletion(groupId, zipBucket, id);
                } catch (Exception e) {
                    log.error("WS task-delete broadcast failed for task {}", id, e);
                }
                deleteR2ObjectsBestEffort(imageKeys, "post " + id);
            }
        });
    }

    /**
     * Best-effort R2 cleanup. Each key is deleted independently with
     * its own try/catch so a single bad key (rotated bucket, missing
     * object, transient network) doesn't strand the rest. Always runs
     * afterCommit so a failed DB transaction never deletes user photos.
     */
    private void deleteR2ObjectsBestEffort(Collection<String> keys, String context) {
        if (keys == null || keys.isEmpty()) return;
        for (String k : keys) {
            if (k == null || k.isBlank()) continue;
            try {
                storage.delete(k);
            } catch (Exception e) {
                log.warn("R2 cleanup failed for {} key={}: {}", context, k, e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------------
    // Broadcast helpers — fire after commit so subscribers don't see a
    // pre-commit state if the transaction rolls back.
    // ---------------------------------------------------------------------

    private PostDto saveAndBroadcast(Post t) {
        Post saved = taskRepo.save(t);
        PostDto dto = withParentPosts(withAuthoredAsGroups(withAuthors(List.of(PostDto.fromEntity(saved))))).get(0);
        broadcastAfterCommit(dto);
        return dto;
    }

    /**
     * Post-conditional-UPDATE re-fetch + broadcast. The {@code @Modifying}
     * transition queries clear the persistence context, so this re-reads
     * the freshly-mutated row to build the DTO that goes back to the
     * caller and over the WebSocket.
     */
    private PostDto refetchAndBroadcast(Long postId) {
        Post fresh = taskRepo.findById(postId)
                .orElseThrow(() -> new IllegalStateException(
                        "Post vanished mid-transition: " + postId));
        PostDto dto = withParentPosts(withAuthoredAsGroups(withAuthors(List.of(PostDto.fromEntity(fresh))))).get(0);
        broadcastAfterCommit(dto);
        return dto;
    }

    private void broadcastAfterCommit(PostDto dto) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    ws.sendPostUpdate(dto);
                } catch (Exception e) {
                    log.error("WS task broadcast failed for task {}", dto.id(), e);
                }
            }
        });
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** For resource-layer ownership / role checks. */
    @Transactional(readOnly = true)
    public Optional<Post> findById(Long id) {
        return id == null ? Optional.empty() : taskRepo.findById(id);
    }

    /**
     * Single-task DTO read with author + engagement folded in. Used by
     * {@code GET /api/tasks/{id}} so the detail page lands with heart
     * count + viewerThanked already populated and doesn't have to fetch
     * reactions separately.
     */
    @Transactional(readOnly = true)
    public Optional<PostDto> findDtoById(Long id, String viewerEmail) {
        if (id == null) return Optional.empty();
        return taskRepo.findById(id)
                .map(PostDto::fromEntity)
                .map(d -> {
                    List<PostDto> folded = withEngagement(withParentPosts(withAuthoredAsGroups(withAuthors(List.of(d)))), viewerEmail);
                    return folded.isEmpty() ? d : folded.get(0);
                });
    }

    /**
     * Public, sanitized preview for social-platform unfurls. Only
     * community-scope posts emit user-generated title/body/image into
     * OpenGraph tags; group-scoped rows return a generic preview so
     * private circle content is not leaked through chat scrapers.
     */
    @Transactional(readOnly = true)
    public Optional<PostSharePreview> findPublicSharePreview(Long id) {
        if (id == null) return Optional.empty();
        return taskRepo.findById(id).map(t -> {
            if (t.getGroupId() != null && !t.getGroupId().isBlank()) {
                return new PostSharePreview(
                        "View this SitPrep post",
                        "Open SitPrep to view this post from your circle.",
                        null
                );
            }

            String author = "a neighbor";
            if (t.getAuthoredAsGroupId() != null && !t.getAuthoredAsGroupId().isBlank()) {
                Group g = groupRepo.findById(t.getAuthoredAsGroupId()).orElse(null);
                if (g != null && g.getGroupName() != null && !g.getGroupName().isBlank()) {
                    author = g.getGroupName().trim();
                }
            } else if (t.getRequesterEmail() != null) {
                UserInfo u = userInfoRepo.findByUserEmailIgnoreCase(t.getRequesterEmail()).orElse(null);
                if (u != null) {
                    String full = (String.valueOf(u.getUserFirstName() == null ? "" : u.getUserFirstName())
                            + " "
                            + String.valueOf(u.getUserLastName() == null ? "" : u.getUserLastName())).trim();
                    if (!full.isBlank()) author = full;
                }
            }

            String title = firstNonBlank(t.getTitle(), excerpt(t.getDescription(), 72), "SitPrep community post");
            StringBuilder desc = new StringBuilder("From ").append(author);
            if (t.getPlaceLabel() != null && !t.getPlaceLabel().isBlank()) {
                desc.append(" near ").append(t.getPlaceLabel().trim());
            }
            String body = excerpt(t.getDescription(), 150);
            if (body != null && !body.equals(title)) {
                desc.append(": ").append(body);
            } else {
                desc.append(" on SitPrep.");
            }
            String imageUrl = t.getImageKeys() == null || t.getImageKeys().isEmpty()
                    ? null
                    : PublicCdn.toPublicUrl(t.getImageKeys().get(0));
            return new PostSharePreview(
                    title.endsWith("on SitPrep") ? title : title + " on SitPrep",
                    desc.toString(),
                    imageUrl
            );
        });
    }

    private Post mustExist(Long id) {
        return taskRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + id));
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private static String excerpt(String value, int max) {
        if (value == null) return null;
        String s = value.trim().replaceAll("\\s+", " ");
        if (s.isBlank()) return null;
        if (max <= 0 || s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)).trim() + "…";
    }

    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                  * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    private static double roundKm(double km) {
        return Math.round(km * 10.0) / 10.0;
    }

    // ---------------------------------------------------------------------
    // Community feed relevance score
    // ---------------------------------------------------------------------

    /** Hard ceiling for "no distance available" — keeps null-distance rows
     *  at the bottom of the score range without going to MAX_VALUE which
     *  would dwarf the recency/engagement boosts on real rows. */
    private static final double SCORE_NULL_DISTANCE_KM = 50.0;
    /** Hard ceiling for "no createdAt" — anything older than 30 days
     *  effectively gets zero recency boost. */
    private static final double SCORE_NULL_AGE_HOURS = 720.0;
    /** Per-km penalty. Same units as distanceKm. */
    private static final double SCORE_DISTANCE_WEIGHT = 1.0;
    /** Recency boost ceiling at age=0; decays via exp(-age/HALF_LIFE). */
    private static final double SCORE_RECENCY_WEIGHT = 8.0;
    /** Half-life of the recency boost in hours. 36h ≈ 1.5 days, which
     *  matches the FE expectation that "today + yesterday" stay fresh,
     *  "this week" is meaningful, and anything older falls off. */
    private static final double SCORE_HALF_LIFE_HOURS = 36.0;
    /** Engagement multiplier on log10(thanks + comments + 1). Small so a
     *  popular old post can't dominate fresh nearby posts; just enough to
     *  break ties within the same proximity band. */
    private static final double SCORE_ENGAGEMENT_WEIGHT = 3.0;

    /** Top-of-feed window for the kind-balance constraint. */
    private static final int KIND_BALANCE_WINDOW = 10;
    /** Max occurrences of any single kind within the top window. */
    private static final int KIND_BALANCE_MAX_PER_KIND = 4;

    /**
     * Kind-balance pass — caps any single {@code kind} at {@link
     * #KIND_BALANCE_MAX_PER_KIND} occurrences within the first {@link
     * #KIND_BALANCE_WINDOW} positions. Items that would exceed the cap
     * get pushed to a deferred queue and re-appended past the window,
     * preserving their relative score order. Past the window everything
     * flows in raw rank order — diversity matters most for the first
     * screen the user sees, after that the feed reads as long-tail.
     *
     * <p>Walks once, O(n). The pin set by {@link #discoverCommunity}
     * survives because alert-update is just one kind among many; even
     * if the user has 5 active alerts, only the first 4 land in the
     * top window and the fifth slides to position 10.</p>
     */
    private static List<PostDto> applyKindBalance(List<PostDto> ranked) {
        if (ranked == null || ranked.size() <= KIND_BALANCE_MAX_PER_KIND) {
            return ranked;
        }
        Map<String, Integer> windowCounts = new HashMap<>();
        List<PostDto> accepted = new ArrayList<>(ranked.size());
        Deque<PostDto> deferred = new ArrayDeque<>();
        for (PostDto d : ranked) {
            if (accepted.size() >= KIND_BALANCE_WINDOW) {
                // Past the window — drain deferred FIRST (so demoted
                // items land just after the window in score order),
                // then accept remaining items as-is.
                while (!deferred.isEmpty()) accepted.add(deferred.poll());
                accepted.add(d);
                continue;
            }
            String k = d.kind() == null ? "post" : d.kind();
            int n = windowCounts.getOrDefault(k, 0);
            if (n >= KIND_BALANCE_MAX_PER_KIND) {
                deferred.offer(d);
                continue;
            }
            accepted.add(d);
            windowCounts.put(k, n + 1);
        }
        // Anything still deferred (e.g. small list never reached the
        // window-end branch) goes at the tail.
        while (!deferred.isEmpty()) accepted.add(deferred.poll());
        return accepted;
    }

    /**
     * Composite community-feed score. Used to sort within-radius rows so
     * the user sees the most relevant content first instead of strict
     * proximity. Higher score = better. See the constants above for the
     * tunable weights.
     *
     * <p>The {@code viaFollow} boost is intentionally NOT applied here
     * because follow-source rows live in a separate {@code followTail}
     * list that's appended after within-radius rows; they have their own
     * recency-only sort.</p>
     */
    private static double communityScore(PostDto d) {
        double dist = d.distanceKm() == null ? SCORE_NULL_DISTANCE_KM : d.distanceKm();
        double ageHours = d.createdAt() == null
                ? SCORE_NULL_AGE_HOURS
                : Duration.between(d.createdAt(), Instant.now()).toMinutes() / 60.0;
        if (ageHours < 0) ageHours = 0; // clock skew safety
        long engagement = d.thanksCount() + d.commentsCount();
        return -SCORE_DISTANCE_WEIGHT * dist
                + SCORE_RECENCY_WEIGHT * Math.exp(-ageHours / SCORE_HALF_LIFE_HOURS)
                + SCORE_ENGAGEMENT_WEIGHT * Math.log10(engagement + 1);
    }
}
