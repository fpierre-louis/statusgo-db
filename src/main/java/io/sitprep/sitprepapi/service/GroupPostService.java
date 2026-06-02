package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.util.GroupUrlUtil;
import io.sitprep.sitprepapi.util.PublicCdn;
import io.sitprep.sitprepapi.domain.GroupPost;
import io.sitprep.sitprepapi.domain.GroupReadState;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.GroupPostDto;
import io.sitprep.sitprepapi.dto.GroupPostPageDto;
import io.sitprep.sitprepapi.dto.EmojiReactionDto;
import io.sitprep.sitprepapi.dto.GroupPostSummaryDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.GroupReadStateRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;

import jakarta.transaction.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * GroupPost create/update/read. Image bytes are no longer handled here —
 * clients upload via {@code POST /api/images} (Cloudflare R2), then
 * attach the returned {@code imageKey} to the post on create/edit.
 * Public delivery URLs are derived via {@link PublicCdn}.
 */
@Service
public class GroupPostService {

    private static final Logger logger = LoggerFactory.getLogger(GroupPostService.class);

    private final GroupPostRepo postRepo;
    private final UserInfoRepo userInfoRepo;
    private final GroupRepo groupRepo;
    private final NotificationService notificationService;
    private final WebSocketMessageSender webSocketMessageSender;
    private final GroupPostReactionService reactionService;
    private final GroupReadStateRepo groupReadStateRepo;
    private final GroupPostThreadPresenceService threadPresenceService;

    @Autowired
    public GroupPostService(GroupPostRepo postRepo, UserInfoRepo userInfoRepo, GroupRepo groupRepo,
                       NotificationService notificationService,
                       WebSocketMessageSender webSocketMessageSender,
                       GroupPostReactionService reactionService,
                       GroupReadStateRepo groupReadStateRepo,
                       GroupPostThreadPresenceService threadPresenceService) {
        this.postRepo = postRepo;
        this.userInfoRepo = userInfoRepo;
        this.groupRepo = groupRepo;
        this.notificationService = notificationService;
        this.webSocketMessageSender = webSocketMessageSender;
        this.reactionService = reactionService;
        this.groupReadStateRepo = groupReadStateRepo;
        this.threadPresenceService = threadPresenceService;
    }

    /** REST creation. Body carries content/group + optional imageKey from /api/images. */
    @Transactional
    public GroupPostDto createPost(GroupPostDto postDto, String actorEmail) {
        if (!actorEmail.equalsIgnoreCase(postDto.getAuthor())) {
            throw new SecurityException("User not authorized to create a post for another user.");
        }

        GroupPost post = new GroupPost();
        post.setAuthor(postDto.getAuthor());
        post.setContent(postDto.getContent());
        post.setGroupId(postDto.getGroupId());
        post.setGroupName(postDto.getGroupName());
        post.setTimestamp(Instant.now());
        post.setTags(postDto.getTags());
        post.setMentions(postDto.getMentions());
        if (postDto.getImageKey() != null && !postDto.getImageKey().isBlank()) {
            post.setImageKey(postDto.getImageKey().trim());
        }

        GroupPost savedPost = postRepo.save(post);
        GroupPostDto savedDto = convertToPostDto(savedPost);
        savedDto.setTempId(postDto.getTempId());
        int deliveredCount = threadPresenceService.openRecipientCount(
                savedPost.getGroupId(), savedPost.getAuthor());
        if (deliveredCount > 0) {
            savedDto.setDeliveredCount(deliveredCount);
            savedDto.setDeliveredAt(Instant.now());
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    notifyGroupMembersOfNewPost(savedPost);
                    webSocketMessageSender.sendNewGroupPost(savedPost.getGroupId(), savedDto);
                } catch (Exception e) {
                    logger.error("GroupPost-commit WS/notify error for post {}", savedPost.getId(), e);
                }
            }
        });

        return savedDto;
    }

    /** WS-path creation (text + optional imageKey). Same shape as REST. */
    @Transactional
    public GroupPostDto createPostFromDto(GroupPostDto postDto, String actorEmail) {
        return createPost(postDto, actorEmail);
    }

    @Transactional
    public GroupPost updatePost(GroupPost post, String actorEmail) {
        if (post.getAuthor() == null || !post.getAuthor().equalsIgnoreCase(actorEmail)) {
            throw new SecurityException("User not authorized to update this post.");
        }

        GroupPost updatedPost = postRepo.save(post);
        GroupPostDto updatedPostDto = convertToPostDto(updatedPost);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                webSocketMessageSender.sendNewGroupPost(updatedPost.getGroupId(), updatedPostDto);
            }
        });

        return updatedPost;
    }

    @Transactional
    public void deletePostAndBroadcast(Long postId, String actorEmail) {
        GroupPost post = postRepo.findById(postId).orElseThrow(() -> new RuntimeException("GroupPost not found"));

        if (post.getAuthor() == null || !post.getAuthor().equalsIgnoreCase(actorEmail)) {
            throw new SecurityException("User not authorized to delete this post.");
        }

        postRepo.delete(post);
        webSocketMessageSender.sendGroupPostDeletion(post.getGroupId(), post.getId());
    }

    @Transactional
    public void updatePostFromDto(GroupPostDto dto) {
        String actorEmail = dto.getAuthor(); // WS path provides author in dto

        GroupPost post = postRepo.findById(dto.getId()).orElseThrow(() ->
                new IllegalArgumentException("GroupPost not found for update: " + dto.getId()));

        if (actorEmail != null && !post.getAuthor().equalsIgnoreCase(actorEmail)) {
            throw new SecurityException("Not allowed to edit this post.");
        }

        post.setContent(dto.getContent());
        post.setEditedAt(Instant.now());
        post.setTags(dto.getTags());
        post.setMentions(dto.getMentions());

        // imageKey present → replace; absent → clear (treats omission as
        // "remove image"). Editors that want to leave the image untouched
        // should pass the existing imageKey back through.
        if (dto.getImageKey() != null && !dto.getImageKey().isBlank()) {
            post.setImageKey(dto.getImageKey().trim());
        } else {
            post.setImageKey(null);
        }

        GroupPost updated = postRepo.save(post);
        GroupPostDto updatedDto = convertToPostDto(updated);
        updatedDto.setTempId(dto.getTempId());

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                webSocketMessageSender.sendNewGroupPost(updated.getGroupId(), updatedDto);
            }
        });
    }

    public List<GroupPost> getPostsByGroupId(String groupId) { return postRepo.findPostsByGroupId(groupId); }

    public List<GroupPostDto> getPostsByGroupSince(String groupId, Instant since) {
        List<GroupPost> rows = postRepo.findByGroupIdAndUpdatedAtAfterOrderByUpdatedAtAsc(groupId, since);
        if (rows.isEmpty()) return List.of();

        Set<String> emails = rows.stream().map(GroupPost::getAuthor).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, UserInfo> userByEmail = userInfoRepo.findByUserEmailIn(new ArrayList<>(emails)).stream()
                .collect(Collectors.toMap(UserInfo::getUserEmail, Function.identity()));

        Map<Long, Map<String, List<EmojiReactionDto>>> reactionsByPost =
                reactionService.loadByPostIds(rows.stream().map(GroupPost::getId).toList());

        List<GroupPostDto> out = new ArrayList<>(rows.size());
        for (GroupPost p : rows) out.add(convertToPostDto(p, userByEmail, reactionsByPost.get(p.getId())));
        applyReadReceipts(out, groupId);
        return out;
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<GroupPostDto> getPostsByGroupIdDto(String groupId) {
        List<GroupPost> posts = postRepo.findPostsByGroupId(groupId);
        if (posts.isEmpty()) return List.of();

        Set<String> emails = posts.stream().map(GroupPost::getAuthor).filter(Objects::nonNull).collect(Collectors.toSet());
        // Pin authors are surfaced by name on the FE ("📌 Pinned by Alice")
        // so include their emails in the batch userinfo lookup. Single
        // round trip; falls back gracefully when a pinner profile is
        // missing (baseDto handles the empty pinnedByFirstName case).
        for (GroupPost p : posts) {
            if (p.getPinnedBy() != null && !p.getPinnedBy().isBlank()) {
                emails.add(p.getPinnedBy());
            }
        }
        Map<String, UserInfo> userByEmail = userInfoRepo.findByUserEmailIn(new ArrayList<>(emails)).stream()
                .collect(Collectors.toMap(UserInfo::getUserEmail, Function.identity()));

        // Batched reaction roster — one repo call for the whole listing.
        Map<Long, Map<String, List<EmojiReactionDto>>> reactionsByPost =
                reactionService.loadByPostIds(posts.stream().map(GroupPost::getId).toList());

        List<GroupPostDto> out = posts.stream()
                .map(p -> convertToPostDto(p, userByEmail, reactionsByPost.get(p.getId())))
                // Sort pinned posts first (by pinnedAt DESC so a recent
                // pin reads above older pins), then everything else by
                // timestamp DESC. The repo returns timestamp-ordered
                // already; this stable sort just lifts the pinned set
                // to the top without disturbing the rest.
                .sorted((a, b) -> {
                    boolean ap = a.getPinnedAt() != null;
                    boolean bp = b.getPinnedAt() != null;
                    if (ap && !bp) return -1;
                    if (!ap && bp) return 1;
                    if (ap && bp) {
                        return b.getPinnedAt().compareTo(a.getPinnedAt());
                    }
                    // Both unpinned — preserve the repo's timestamp DESC
                    // by comparing timestamps directly (defensive — the
                    // repo query already orders this way, but explicit
                    // here means the sort is total and stable).
                    if (a.getTimestamp() == null) return 1;
                    if (b.getTimestamp() == null) return -1;
                    return b.getTimestamp().compareTo(a.getTimestamp());
                })
                .toList();
        applyReadReceipts(out, groupId);
        return out;
    }

    /**
     * Cursor-paginated chat-feed listing. Returns:
     * <ul>
     *   <li>All pinned posts for the group (admins typically pin 0-3,
     *       so cardinality is bounded — always-on-top regardless of
     *       scroll depth).</li>
     *   <li>A page of unpinned posts in id DESC order, cursored by
     *       {@code before}.</li>
     *   <li>A {@code nextBefore} cursor for the next page, plus a
     *       {@code hasMore} hint.</li>
     * </ul>
     *
     * <p>Scale rationale: the legacy {@link #getPostsByGroupIdDto(String)}
     * loads ALL posts at once — fine for the first quarter of a group's
     * life, fatal at 1k+ rows. This paginated path is the canonical
     * listing surface for the chat feed; the legacy method stays
     * available for any caller that genuinely needs the whole set
     * (none currently — usePostWebSocket migrates here in the FE
     * pass shipped alongside this BE change).</p>
     *
     * <p>Limit is clamped to [1, 200]. Default size is 50 — matches the
     * sustainable rendering load on phones for chat-style scroll
     * (similar to PostCommentService.getCommentsPage's default).</p>
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public GroupPostPageDto getPostsByGroupIdPage(String groupId, Long before, Integer limit) {
        int pageSize = (limit == null) ? 50 : Math.max(1, Math.min(200, limit));

        // Two queries: pinned (small, unbounded set) + unpinned (bounded
        // by pageSize). The pinned set is queried fresh on every page
        // fetch — cheap because admins rarely pin more than a handful
        // and the index on (group_id, pinned_at) makes the scan trivial.
        List<GroupPost> pinned = postRepo.findPinnedByGroupId(groupId);
        // Fetch pageSize+1 to detect hasMore without a second query.
        List<GroupPost> unpinned = postRepo.findUnpinnedByGroupIdPage(
                groupId,
                before,
                org.springframework.data.domain.PageRequest.of(0, pageSize + 1)
        );

        boolean hasMore = unpinned.size() > pageSize;
        if (hasMore) {
            unpinned = unpinned.subList(0, pageSize);
        }
        Long nextBefore = hasMore && !unpinned.isEmpty()
                ? unpinned.get(unpinned.size() - 1).getId()
                : null;

        // Batch the userinfo lookup across pinned + unpinned authors +
        // pinners so the response is one-round-trip-per-fetch regardless
        // of page size. Single SELECT, regardless of how many distinct
        // emails appear.
        Set<String> emails = new HashSet<>();
        for (GroupPost p : pinned) {
            if (p.getAuthor() != null) emails.add(p.getAuthor());
            if (p.getPinnedBy() != null && !p.getPinnedBy().isBlank()) emails.add(p.getPinnedBy());
        }
        for (GroupPost p : unpinned) {
            if (p.getAuthor() != null) emails.add(p.getAuthor());
        }
        Map<String, UserInfo> userByEmail = emails.isEmpty()
                ? Collections.emptyMap()
                : userInfoRepo.findByUserEmailIn(new ArrayList<>(emails)).stream()
                        .collect(Collectors.toMap(UserInfo::getUserEmail, Function.identity()));

        // Batched reactions for both pinned + unpinned. Single round-trip.
        List<Long> allIds = new ArrayList<>(pinned.size() + unpinned.size());
        for (GroupPost p : pinned) allIds.add(p.getId());
        for (GroupPost p : unpinned) allIds.add(p.getId());
        Map<Long, Map<String, List<EmojiReactionDto>>> reactionsByPost =
                allIds.isEmpty()
                        ? Collections.emptyMap()
                        : reactionService.loadByPostIds(allIds);

        List<GroupPostDto> pinnedDtos = pinned.stream()
                .map(p -> convertToPostDto(p, userByEmail, reactionsByPost.get(p.getId())))
                .toList();
        List<GroupPostDto> unpinnedDtos = unpinned.stream()
                .map(p -> convertToPostDto(p, userByEmail, reactionsByPost.get(p.getId())))
                .toList();
        applyReadReceipts(pinnedDtos, groupId);
        applyReadReceipts(unpinnedDtos, groupId);

        return new GroupPostPageDto(pinnedDtos, unpinnedDtos, nextBefore, hasMore);
    }

    public Optional<GroupPost> getPostById(Long id) { return postRepo.findById(id); }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<GroupPostDto> getPostDtoById(Long id) {
        return postRepo.findById(id).map(post -> {
            GroupPostDto dto = convertToPostDto(post);
            applyReadReceipts(List.of(dto), post.getGroupId());
            return dto;
        });
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Map<String, GroupPostSummaryDto> getLatestPostsForGroups(List<String> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) return Collections.emptyMap();

        List<GroupPost> candidates = postRepo.findLatestPostsByGroupIds(groupIds);
        Map<String, GroupPost> bestByGroup = new HashMap<>();
        for (GroupPost p : candidates) {
            GroupPost cur = bestByGroup.get(p.getGroupId());
            if (cur == null || p.getTimestamp().isAfter(cur.getTimestamp())
                    || (p.getTimestamp().equals(cur.getTimestamp()) && p.getId() > cur.getId())) {
                bestByGroup.put(p.getGroupId(), p);
            }
        }

        Set<String> emails = bestByGroup.values().stream().map(GroupPost::getAuthor).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, UserInfo> userByEmail = userInfoRepo.findByUserEmailIn(new ArrayList<>(emails)).stream()
                .collect(Collectors.toMap(UserInfo::getUserEmail, Function.identity()));

        Map<String, GroupPostSummaryDto> out = new HashMap<>();
        for (var e : bestByGroup.entrySet()) {
            GroupPost p = e.getValue();
            UserInfo u = userByEmail.get(p.getAuthor());

            GroupPostSummaryDto dto = new GroupPostSummaryDto();
            dto.setId(p.getId());
            dto.setGroupId(p.getGroupId());
            dto.setGroupName(p.getGroupName());
            dto.setAuthor(p.getAuthor());
            if (u != null) {
                dto.setAuthorFirstName(u.getUserFirstName());
                dto.setAuthorLastName(u.getUserLastName());
                dto.setAuthorProfileImageURL(u.getProfileImageURL());
            }
            dto.setContent(p.getContent());
            dto.setTimestamp(p.getTimestamp());

            out.put(e.getKey(), dto);
        }
        return out;
    }

    private void notifyGroupMembersOfNewPost(GroupPost post) {
        groupRepo.findByGroupId(post.getGroupId()).ifPresentOrElse(group -> {
            var recipientEmails = group.getMemberEmails() == null ? List.<String>of() :
                    group.getMemberEmails().stream().filter(e -> !e.equalsIgnoreCase(post.getAuthor())).toList();

            if (recipientEmails.isEmpty()) {
                logger.warn("No recipients for group {}", group.getGroupName());
                return;
            }

            var authorOpt = userInfoRepo.findByUserEmail(post.getAuthor());
            String authorFirst = authorOpt.map(UserInfo::getUserFirstName).orElse("Someone");
            String authorProfile = authorOpt.map(UserInfo::getProfileImageURL).orElse("/images/default-user-icon.png");

            String title = group.getGroupName();
            String snippet = post.getContent() == null ? "" :
                    (post.getContent().length() > 50 ? post.getContent().substring(0, 50) + "..." : post.getContent());
            String body = String.format("%s posted in %s: '%s'", authorFirst, group.getGroupName(), snippet);

            String baseTargetUrl = GroupUrlUtil.getGroupTargetUrl(group);
            String targetUrl = baseTargetUrl + "?postId=" + post.getId();

            List<UserInfo> users = userInfoRepo.findByUserEmailIn(recipientEmails);
            for (UserInfo user : users) {
                // Mute-aware variant: when the recipient has muted
                // this circle, FCM + STOMP banner are skipped (an
                // inbox row is still written so missed messages are
                // visible after unmute).
                notificationService.deliverPresenceAwareForGroup(
                        user.getUserEmail(), title, body, authorFirst, authorProfile,
                        "post_notification", post.getGroupId(), targetUrl, String.valueOf(post.getId()),
                        user.getFcmtoken(),
                        post.getGroupId()
                );
            }

            logger.info("GroupPost notification sent for '{}' to {} members.", group.getGroupName(), users.size());
        }, () -> logger.warn("Group with ID {} not found (notify)", post.getGroupId()));
    }

    private GroupPostDto convertToPostDto(GroupPost post) {
        GroupPostDto dto = baseDto(post, reactionService.loadByPostId(post.getId()));
        userInfoRepo.findByUserEmail(post.getAuthor()).ifPresent(u -> {
            dto.setAuthorFirstName(u.getUserFirstName());
            dto.setAuthorLastName(u.getUserLastName());
            dto.setAuthorProfileImageURL(u.getProfileImageURL());
        });
        return dto;
    }

    private GroupPostDto convertToPostDto(GroupPost post,
                                     Map<String, UserInfo> userByEmail,
                                     Map<String, List<EmojiReactionDto>> reactions) {
        GroupPostDto dto = baseDto(post, reactions);
        UserInfo u = userByEmail.get(post.getAuthor());
        if (u != null) {
            dto.setAuthorFirstName(u.getUserFirstName());
            dto.setAuthorLastName(u.getUserLastName());
            dto.setAuthorProfileImageURL(u.getProfileImageURL());
        }
        return dto;
    }

    private GroupPostDto baseDto(GroupPost post, Map<String, List<EmojiReactionDto>> reactions) {
        GroupPostDto dto = new GroupPostDto();
        dto.setId(post.getId());
        dto.setAuthor(post.getAuthor());
        dto.setContent(post.getContent());
        dto.setGroupId(String.valueOf(post.getGroupId()));
        dto.setGroupName(post.getGroupName());
        dto.setTimestamp(post.getTimestamp());
        dto.setEditedAt(post.getEditedAt());
        dto.setUpdatedAt(post.getUpdatedAt());
        if (post.getImageKey() != null && !post.getImageKey().isBlank()) {
            dto.setImageKey(post.getImageKey());
            dto.setImageUrl(PublicCdn.toPublicUrl(post.getImageKey()));
        }
        dto.setReactions(reactions != null ? reactions : new HashMap<>());
        dto.setTags(post.getTags() != null ? post.getTags() : new ArrayList<>());
        dto.setMentions(post.getMentions() != null ? post.getMentions() : new ArrayList<>());
        dto.setCommentsCount(post.getCommentsCount());
        // Pin metadata — denormalize the pinner's first name when set so
        // the FE can render "📌 Pinned by Alice" without a per-card
        // profile fetch. Single-row lookup; no impact on the batched
        // listing path's other passes. Falls back silently when the
        // pinner profile can't be resolved.
        dto.setPinnedAt(post.getPinnedAt());
        dto.setPinnedBy(post.getPinnedBy());
        if (post.getPinnedBy() != null && !post.getPinnedBy().isBlank()) {
            userInfoRepo.findByUserEmail(post.getPinnedBy()).ifPresent(u ->
                    dto.setPinnedByFirstName(u.getUserFirstName()));
        }
        return dto;
    }

    private void applyReadReceipts(List<GroupPostDto> posts, String groupId) {
        if (posts == null || posts.isEmpty() || groupId == null || groupId.isBlank()) return;
        List<GroupReadState> states = groupReadStateRepo.findByGroupId(groupId);
        if (states.isEmpty()) return;

        for (GroupPostDto post : posts) {
            if (post.getTimestamp() == null) continue;
            Instant latest = null;
            int count = 0;
            for (GroupReadState state : states) {
                if (state.getLastReadAt() == null || state.getUserEmail() == null) continue;
                if (post.getAuthor() != null && state.getUserEmail().equalsIgnoreCase(post.getAuthor())) {
                    continue;
                }
                if (!state.getLastReadAt().isBefore(post.getTimestamp())) {
                    count++;
                    if (latest == null || state.getLastReadAt().isAfter(latest)) {
                        latest = state.getLastReadAt();
                    }
                }
            }
            post.setReadCount(count);
            post.setReadAt(latest);
        }
    }

    /**
     * Pin a post to the top of its group's feed. The caller must be an
     * admin or owner of the post's group — otherwise rejected with 403.
     * Multiple posts can be pinned in the same group; they sort by
     * {@code pinnedAt DESC} so the most-recently-pinned reads first.
     *
     * <p>Idempotent: pinning an already-pinned post bumps {@code pinnedAt}
     * (and {@code pinnedBy} if a different admin re-pins). The FE
     * doesn't need to check current state before calling.</p>
     */
    @Transactional
    public GroupPostDto pinPost(Long postId, String actorEmail) {
        if (actorEmail == null || actorEmail.isBlank()) {
            throw new IllegalArgumentException("actorEmail required");
        }
        GroupPost post = postRepo.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Post not found"));
        requireGroupAdmin(post.getGroupId(), actorEmail);
        post.setPinnedAt(Instant.now());
        post.setPinnedBy(actorEmail.trim().toLowerCase());
        GroupPost saved = postRepo.save(post);
        GroupPostDto dto = convertToPostDto(saved);
        broadcastUpdated(dto);
        return dto;
    }

    /**
     * Unpin a previously-pinned post. Clears both {@link GroupPost#pinnedAt}
     * and {@link GroupPost#pinnedBy} so the FE drops the "Pinned by"
     * chip on the next render. Same admin gate as
     * {@link #pinPost(Long, String)}.
     *
     * <p>Idempotent: unpinning an already-unpinned post is a no-op.</p>
     */
    @Transactional
    public GroupPostDto unpinPost(Long postId, String actorEmail) {
        if (actorEmail == null || actorEmail.isBlank()) {
            throw new IllegalArgumentException("actorEmail required");
        }
        GroupPost post = postRepo.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Post not found"));
        requireGroupAdmin(post.getGroupId(), actorEmail);
        post.setPinnedAt(null);
        post.setPinnedBy(null);
        GroupPost saved = postRepo.save(post);
        GroupPostDto dto = convertToPostDto(saved);
        broadcastUpdated(dto);
        return dto;
    }

    /**
     * Throw 403-equivalent when {@code email} isn't an admin or owner
     * of {@code groupId}. Centralized so pin/unpin (and future admin-
     * gated GroupPost actions) share one gate.
     */
    private void requireGroupAdmin(String groupId, String email) {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("Post has no group");
        }
        String me = email.trim().toLowerCase();
        groupRepo.findByGroupId(groupId).ifPresentOrElse(g -> {
            boolean isAdmin = g.getAdminEmails() != null && g.getAdminEmails().stream()
                    .anyMatch(e -> e != null && e.equalsIgnoreCase(me));
            boolean isOwner = g.getOwnerEmail() != null && g.getOwnerEmail().equalsIgnoreCase(me);
            if (!isAdmin && !isOwner) {
                throw new SecurityException("Only group admins can pin posts");
            }
        }, () -> {
            throw new IllegalArgumentException("Group not found");
        });
    }

    /**
     * Broadcast a pin/unpin (or other update) via the existing
     * {@code sendNewGroupPost} fan-out path. The FE chat surface
     * listens for {@code /topic/group-posts/{groupId}} frames and
     * merges by id, so an update is just "send the same id again with
     * the new fields" — no separate WS verb needed.
     */
    private void broadcastUpdated(GroupPostDto dto) {
        try {
            webSocketMessageSender.sendNewGroupPost(dto.getGroupId(), dto);
        } catch (Exception e) {
            logger.warn("Failed to broadcast pin update for post {}: {}",
                    dto.getId(), e.getMessage());
        }
    }
}
