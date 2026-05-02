package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.PostKind;
import io.sitprep.sitprepapi.domain.Follow;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Post.PostStatus;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.repo.FollowRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    public PostService(PostRepo taskRepo, UserInfoRepo userInfoRepo,
                       NominatimGeocodeService geocode,
                       WebSocketMessageSender ws,
                       AlertModeService alertModeService,
                       FollowRepo followRepo,
                       BlockService blockService,
                       PostReactionService reactionService,
                       PostCommentService commentService) {
        this.taskRepo = taskRepo;
        this.userInfoRepo = userInfoRepo;
        this.geocode = geocode;
        this.ws = ws;
        this.alertModeService = alertModeService;
        this.followRepo = followRepo;
        this.blockService = blockService;
        this.reactionService = reactionService;
        this.commentService = commentService;
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
     * Batch-fold engagement counts (heart "Thank" count, viewer-thanked
     * flag, and — once Phase 2 ships — comment count) onto a list of
     * TaskDtos. Single batched query for the reaction summary so a
     * 50-row feed page is one extra DB round trip total, not 50.
     *
     * <p>{@code viewerEmail} null means an unauthenticated read (or a
     * read where viewer identity isn't available); {@code viewerThanked}
     * defaults to false everywhere. Counts always populate regardless.</p>
     *
     * <p>Phase 2 (2026-05-04) wires comment counts via {@code PostCommentService.loadCountsByTaskIds}
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
        PostReactionService.ThankSummary summary =
                reactionService.loadThankSummary(ids, viewerEmail);
        Map<Long, Integer> commentCounts = commentService.loadCountsByTaskIds(ids);
        return dtos.stream()
                .map(d -> {
                    if (d.id() == null) return d;
                    return d.withEngagement(
                            summary.countFor(d.id()),
                            summary.viewerThankedTask(d.id()),
                            commentCounts.getOrDefault(d.id(), 0)
                    );
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
        // Title goes through per-kind validation below; description first
        // so the validation can require one when title is absent.
        t.setDescription(incoming.getDescription());
        t.setStatus(incoming.getStatus() != null ? incoming.getStatus() : PostStatus.OPEN);
        t.setPriority(incoming.getPriority() != null ? incoming.getPriority() : Post.PostPriority.MEDIUM);
        t.setLatitude(incoming.getLatitude());
        t.setLongitude(incoming.getLongitude());
        t.setDueAt(incoming.getDueAt());
        t.setParentTaskId(incoming.getParentTaskId());
        if (incoming.getTags() != null) t.getTags().addAll(incoming.getTags());
        if (incoming.getImageKeys() != null) t.getImageKeys().addAll(incoming.getImageKeys());

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
            // still send. Description must be present so the card has
            // something to render.
            if (incoming.getDescription() == null || incoming.getDescription().isBlank()) {
                throw new IllegalArgumentException(
                        "description required for kind=" + t.getKind());
            }
            t.setTitle(null);
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

        Post saved = taskRepo.save(t);
        PostDto dto = PostDto.fromEntity(saved);
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
        return withEngagement(withAuthors(dtos), viewerEmail);
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
        return withEngagement(withAuthors(dtos), email);
    }

    @Transactional(readOnly = true)
    public List<PostDto> listClaimedBy(String email) {
        if (email == null || email.isBlank()) return List.of();
        // Claimer is the viewer for this surface (same reasoning as listRequestedBy).
        List<PostDto> dtos = taskRepo.findByClaimedByEmailIgnoreCaseOrderByCreatedAtDesc(email).stream()
                .map(PostDto::fromEntity).collect(Collectors.toList());
        return withEngagement(withAuthors(dtos), email);
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
        // null distance sorts last (after all geo-tagged within-radius tasks),
        // which matches the FE proximity-score expectation: nearby first,
        // then community-wide.
        within.sort(Comparator.comparingDouble(d -> d.distanceKm() == null ? Double.MAX_VALUE : d.distanceKm()));

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

        List<PostDto> capped = filtered.size() > 50 ? filtered.subList(0, 50) : filtered;
        return withEngagement(withAuthors(capped), viewerEmail);
    }

    // ---------------------------------------------------------------------
    // Lifecycle: claim, complete, cancel, reopen, patch, delete
    // ---------------------------------------------------------------------

    /**
     * A group leader claims a task on behalf of their group. The task must
     * currently be unclaimed and OPEN. Caller email must be admin/owner of
     * the claimer group — checked at the resource layer; this service trusts
     * the caller has been authorized.
     */
    @Transactional
    public PostDto claim(Long taskId, String claimerGroupId, String claimerEmail) {
        Post t = mustExist(taskId);
        if (t.getStatus() != PostStatus.OPEN || t.getClaimedByGroupId() != null) {
            throw new IllegalStateException("Post is not open for claim");
        }
        t.setClaimedByGroupId(claimerGroupId);
        t.setClaimedByEmail(claimerEmail == null ? null : claimerEmail.trim().toLowerCase());
        t.setStatus(PostStatus.CLAIMED);
        t.setClaimedAt(Instant.now());
        return saveAndBroadcast(t);
    }

    /** Mark in-progress (claimer is actively working). */
    @Transactional
    public PostDto markInProgress(Long taskId) {
        Post t = mustExist(taskId);
        if (t.getStatus() != PostStatus.CLAIMED) {
            throw new IllegalStateException("Post must be claimed before marking in-progress");
        }
        t.setStatus(PostStatus.IN_PROGRESS);
        return saveAndBroadcast(t);
    }

    /** Claimer marks complete. */
    @Transactional
    public PostDto complete(Long taskId) {
        Post t = mustExist(taskId);
        if (t.getStatus() == PostStatus.DONE || t.getStatus() == PostStatus.CANCELLED) {
            throw new IllegalStateException("Post is already closed");
        }
        t.setStatus(PostStatus.DONE);
        t.setCompletedAt(Instant.now());
        return saveAndBroadcast(t);
    }

    /** Requester cancels. Frees claimedBy state in case it was claimed. */
    @Transactional
    public PostDto cancel(Long taskId) {
        Post t = mustExist(taskId);
        if (t.getStatus() == PostStatus.DONE) {
            throw new IllegalStateException("Cannot cancel a completed task");
        }
        t.setStatus(PostStatus.CANCELLED);
        return saveAndBroadcast(t);
    }

    /** Reopen a cancelled task — clears claimer state. */
    @Transactional
    public PostDto reopen(Long taskId) {
        Post t = mustExist(taskId);
        if (t.getStatus() != PostStatus.CANCELLED) {
            throw new IllegalStateException("Only cancelled tasks can be reopened");
        }
        t.setStatus(PostStatus.OPEN);
        t.setClaimedByGroupId(null);
        t.setClaimedByEmail(null);
        t.setClaimedAt(null);
        return saveAndBroadcast(t);
    }

    /**
     * Author-only partial update — title, description, priority, dueAt,
     * tags, imageKeys, latitude/longitude. Lifecycle fields (status,
     * claimer*, claimedAt, completedAt) flow through dedicated methods.
     */
    @Transactional
    public PostDto patch(Long taskId, Post patch) {
        Post t = mustExist(taskId);
        if (patch.getTitle() != null) t.setTitle(patch.getTitle());
        if (patch.getDescription() != null) t.setDescription(patch.getDescription());
        if (patch.getPriority() != null) t.setPriority(patch.getPriority());
        if (patch.getDueAt() != null) t.setDueAt(patch.getDueAt());
        if (patch.getTags() != null) {
            t.getTags().clear();
            t.getTags().addAll(patch.getTags());
        }
        if (patch.getImageKeys() != null) {
            t.getImageKeys().clear();
            t.getImageKeys().addAll(patch.getImageKeys());
        }
        if (patch.getLatitude() != null) t.setLatitude(patch.getLatitude());
        if (patch.getLongitude() != null) t.setLongitude(patch.getLongitude());
        return saveAndBroadcast(t);
    }

    @Transactional
    public void delete(Long taskId) {
        Post t = taskRepo.findById(taskId).orElse(null);
        if (t == null) return;
        String groupId = t.getGroupId();
        String zipBucket = t.getZipBucket();
        Long id = t.getId();
        taskRepo.delete(t);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    ws.sendTaskDeletion(groupId, zipBucket, id);
                } catch (Exception e) {
                    log.error("WS task-delete broadcast failed for task {}", id, e);
                }
            }
        });
    }

    // ---------------------------------------------------------------------
    // Broadcast helpers — fire after commit so subscribers don't see a
    // pre-commit state if the transaction rolls back.
    // ---------------------------------------------------------------------

    private PostDto saveAndBroadcast(Post t) {
        Post saved = taskRepo.save(t);
        PostDto dto = PostDto.fromEntity(saved);
        broadcastAfterCommit(dto);
        return dto;
    }

    private void broadcastAfterCommit(PostDto dto) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    ws.sendTaskUpdate(dto);
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
                    List<PostDto> folded = withEngagement(withAuthors(List.of(d)), viewerEmail);
                    return folded.isEmpty() ? d : folded.get(0);
                });
    }

    private Post mustExist(Long id) {
        return taskRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + id));
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
}
