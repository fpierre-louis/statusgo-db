package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.GroupPostComment;
import io.sitprep.sitprepapi.domain.GroupPost;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.EmojiReactionDto;
import io.sitprep.sitprepapi.dto.GroupPostCommentDto;
import io.sitprep.sitprepapi.repo.GroupPostCommentRepo;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * GroupPostComment service:
 * - Creates/updates/deletes comments
 * - Broadcasts EXACTLY ONCE after successful commit
 * - Enriches author fields for clients (name, avatar)
 * - Sends presence-aware notifications (e.g., "comment on your post")
 */
@Service
public class GroupPostCommentService {

    private static final Logger log = LoggerFactory.getLogger(GroupPostCommentService.class);

    private final GroupPostCommentRepo commentRepo;
    private final GroupPostRepo postRepo;
    private final UserInfoRepo userInfoRepo;
    private final WebSocketMessageSender ws;
    private final NotificationService notificationService;
    private final GroupPostCommentReactionService reactionService;

    public GroupPostCommentService(
            GroupPostCommentRepo commentRepo,
            GroupPostRepo postRepo,
            UserInfoRepo userInfoRepo,
            WebSocketMessageSender ws,
            NotificationService notificationService,
            GroupPostCommentReactionService reactionService
    ) {
        this.commentRepo = commentRepo;
        this.postRepo = postRepo;
        this.userInfoRepo = userInfoRepo;
        this.ws = ws;
        this.notificationService = notificationService;
        this.reactionService = reactionService;
    }

    // --------------------------------------------------------------------------------------------
    // Create (REST or WS)
    // --------------------------------------------------------------------------------------------
    @Transactional
    public GroupPostCommentDto createCommentFromDto(GroupPostCommentDto dto) {
        if (dto == null) throw new IllegalArgumentException("GroupPostCommentDto is null");
        if (dto.getPostId() == null) throw new IllegalArgumentException("postId is required");
        if (dto.getContent() == null || dto.getContent().trim().isEmpty()) {
            throw new IllegalArgumentException("content is required");
        }
        if (dto.getAuthor() == null || dto.getAuthor().trim().isEmpty()) {
            dto.setAuthor("anonymous@sitprep");
        }

        GroupPostComment c = new GroupPostComment();
        c.setPostId(dto.getPostId());
        c.setAuthor(dto.getAuthor().trim());
        c.setContent(dto.getContent());
        // @CreatedDate/@LastModifiedDate auditing populates timestamp/updatedAt

        GroupPostComment saved = commentRepo.save(c);
        bumpCommentsCount(saved.getPostId(), +1);

        GroupPostCommentDto out = toDto(saved);
        // preserve tempId for optimistic merge on the client
        out.setTempId(dto.getTempId());
        enrichAuthor(out);

        // Broadcast AFTER COMMIT to avoid duplicates on rollback
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    ws.sendNewGroupPostComment(saved.getPostId(), out);
                } catch (Exception e) {
                    log.error("WS broadcast failed for new comment id={}", saved.getId(), e);
                }

                try {
                    notifyPostAuthorOnNewComment(saved, out);
                } catch (Exception e) {
                    log.error("Notification fan-out failed for new comment id={}", saved.getId(), e);
                }
            }
        });

        return out;
    }

    // --------------------------------------------------------------------------------------------
    // Update (REST or WS)
    // --------------------------------------------------------------------------------------------
    @Transactional
    public GroupPostCommentDto updateCommentFromDto(GroupPostCommentDto dto) {
        if (dto == null) throw new IllegalArgumentException("GroupPostCommentDto is null");
        if (dto.getId() == null) throw new IllegalArgumentException("id is required for update");

        GroupPostComment existing = commentRepo.findById(dto.getId())
                .orElseThrow(() -> new IllegalArgumentException("GroupPostComment not found: " + dto.getId()));

        // Minimal ownership check (MVP)
        if (dto.getAuthor() != null && !dto.getAuthor().isBlank()) {
            if (existing.getAuthor() != null &&
                    !existing.getAuthor().equalsIgnoreCase(dto.getAuthor().trim())) {
                throw new SecurityException("Not allowed to edit this comment.");
            }
        }

        if (dto.getContent() != null && !dto.getContent().trim().isEmpty()) {
            existing.setContent(dto.getContent());
        }
        existing.setEditedAt(Instant.now());

        GroupPostComment saved = commentRepo.save(existing);

        GroupPostCommentDto out = toDto(saved);
        out.setTempId(dto.getTempId()); // keep optimistic correlation if present
        out.setEdited(true);
        enrichAuthor(out);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    // Reuse same topic for create/edit deltas
                    ws.sendNewGroupPostComment(saved.getPostId(), out);
                } catch (Exception e) {
                    log.error("WS broadcast failed for edit comment id={}", saved.getId(), e);
                }
            }
        });

        return out;
    }

    // --------------------------------------------------------------------------------------------
    // Delete (WS path with known postId)
    // --------------------------------------------------------------------------------------------
    @Transactional
    public void deleteCommentAndBroadcast(Long commentId, Long postId) {
        if (commentId == null || postId == null) return;

        if (!commentRepo.existsById(commentId)) return;

        commentRepo.deleteById(commentId);
        bumpCommentsCount(postId, -1);

        Long pid = postId;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    ws.sendGroupPostCommentDeletion(pid, commentId);
                } catch (Exception e) {
                    log.error("WS broadcast failed for delete comment id={}", commentId, e);
                }
            }
        });
    }

    // --------------------------------------------------------------------------------------------
    // Delete (REST path: look up postId)
    // --------------------------------------------------------------------------------------------
    @Transactional
    public void deleteCommentByIdAndBroadcast(Long id) {
        if (id == null) return;
        GroupPostComment c = commentRepo.findById(id).orElse(null);
        if (c == null) return;

        Long postId = c.getPostId();
        commentRepo.deleteById(id);
        bumpCommentsCount(postId, -1);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    ws.sendGroupPostCommentDeletion(postId, id);
                } catch (Exception e) {
                    log.error("WS broadcast failed for delete comment id={}", id, e);
                }
            }
        });
    }

    // --------------------------------------------------------------------------------------------
    // Queries
    // --------------------------------------------------------------------------------------------
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<GroupPostCommentDto> getCommentsByPostId(Long postId, String viewerEmail) {
        if (postId == null) return List.of();

        List<GroupPostComment> rows = commentRepo.findByPostIdOrderByTimestampAsc(postId);
        if (rows.isEmpty()) return List.of();

        Set<String> emails = rows.stream()
                .map(GroupPostComment::getAuthor).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, UserInfo> userByEmail = userInfoRepo.findByUserEmailIn(new ArrayList<>(emails))
                .stream().collect(Collectors.toMap(UserInfo::getUserEmail, Function.identity()));

        List<GroupPostCommentDto> dtos = rows.stream()
                .map(c -> toDto(c, userByEmail)).collect(Collectors.toList());
        return withReactions(dtos, viewerEmail);
    }

    /** Back-compat overload — viewerThanked stays false for callers without identity. */
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<GroupPostCommentDto> getCommentsByPostId(Long postId) {
        return getCommentsByPostId(postId, null);
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Map<Long, List<GroupPostCommentDto>> getCommentsForPosts(
            List<Long> postIds, Integer limitPerPost, String viewerEmail) {
        if (postIds == null || postIds.isEmpty()) return Map.of();

        List<GroupPostComment> rows = commentRepo.findAllByPostIdInOrderByPostIdAscTimestampAsc(postIds);
        if (rows.isEmpty()) return Map.of();

        Map<Long, List<GroupPostComment>> byPost = rows.stream()
                .collect(Collectors.groupingBy(GroupPostComment::getPostId, LinkedHashMap::new, Collectors.toList()));

        Set<String> emails = rows.stream()
                .map(GroupPostComment::getAuthor).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, UserInfo> userByEmail = userInfoRepo.findByUserEmailIn(new ArrayList<>(emails))
                .stream().collect(Collectors.toMap(UserInfo::getUserEmail, Function.identity()));

        // Flatten + fold reactions across the whole batch in one pair of
        // queries, then re-group by postId. Cheaper than per-post folding
        // when the FE asks for many posts at once.
        Map<Long, List<GroupPostCommentDto>> out = new LinkedHashMap<>();
        List<GroupPostCommentDto> all = new ArrayList<>();
        for (var e : byPost.entrySet()) {
            List<GroupPostComment> list = e.getValue();
            if (limitPerPost != null && limitPerPost > 0 && list.size() > limitPerPost) {
                list = list.subList(list.size() - limitPerPost, list.size()); // last N
            }
            List<GroupPostCommentDto> bucket = list.stream()
                    .map(c -> toDto(c, userByEmail)).collect(Collectors.toList());
            all.addAll(bucket);
            out.put(e.getKey(), bucket);
        }
        withReactions(all, viewerEmail);
        return out;
    }

    /** Back-compat overload. */
    @Transactional(Transactional.TxType.SUPPORTS)
    public Map<Long, List<GroupPostCommentDto>> getCommentsForPosts(
            List<Long> postIds, Integer limitPerPost) {
        return getCommentsForPosts(postIds, limitPerPost, null);
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<GroupPostCommentDto> getCommentsSince(Long postId, Instant since, String viewerEmail) {
        if (postId == null || since == null) return List.of();

        var rows = commentRepo.findByPostIdAndUpdatedAtAfterOrderByUpdatedAtAsc(postId, since);
        if (rows.isEmpty()) return List.of();

        Set<String> emails = rows.stream()
                .map(GroupPostComment::getAuthor).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, UserInfo> userByEmail = userInfoRepo.findByUserEmailIn(new ArrayList<>(emails))
                .stream().collect(Collectors.toMap(UserInfo::getUserEmail, Function.identity()));

        List<GroupPostCommentDto> dtos = rows.stream()
                .map(c -> toDto(c, userByEmail)).collect(Collectors.toList());
        return withReactions(dtos, viewerEmail);
    }

    /** Back-compat overload. */
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<GroupPostCommentDto> getCommentsSince(Long postId, Instant since) {
        return getCommentsSince(postId, since, null);
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<GroupPostCommentDto> getCommentById(Long id) {
        if (id == null) return Optional.empty();
        return commentRepo.findById(id).map(c -> {
            GroupPostCommentDto d = toDto(c);
            enrichAuthor(d);
            d.setReactions(reactionService.loadByGroupPostCommentId(d.getId()));
            return d;
        });
    }

    /**
     * Batch-fold per-emoji roster + heart "Thank" count + viewer-thanked
     * flag onto a list of GroupPostCommentDtos. Mirrors
     * {@code PostCommentService.withReactions}.
     */
    private List<GroupPostCommentDto> withReactions(List<GroupPostCommentDto> dtos, String viewerEmail) {
        if (dtos == null || dtos.isEmpty()) return dtos;
        List<Long> ids = dtos.stream()
                .map(GroupPostCommentDto::getId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) return dtos;
        Map<Long, Map<String, List<EmojiReactionDto>>> rosterByComment =
                reactionService.loadByGroupPostCommentIds(ids);
        GroupPostCommentReactionService.ThankSummary summary =
                reactionService.loadThankSummary(ids, viewerEmail);
        for (GroupPostCommentDto d : dtos) {
            if (d.getId() == null) continue;
            d.setReactions(rosterByComment.getOrDefault(d.getId(), Map.of()));
            d.setThanksCount(summary.countFor(d.getId()));
            d.setViewerThanked(summary.viewerThankedComment(d.getId()));
        }
        return dtos;
    }

    // --------------------------------------------------------------------------------------------
    // Internals
    // --------------------------------------------------------------------------------------------
    private void bumpCommentsCount(Long postId, int delta) {
        if (postId == null || delta == 0) return;
        try {
            postRepo.findById(postId).ifPresent(p -> {
                Integer cur = p.getCommentsCount();
                int next = (cur == null ? 0 : cur) + delta;
                if (next < 0) next = 0;
                p.setCommentsCount(next);
                postRepo.save(p);
            });
        } catch (Exception e) {
            log.warn("Could not update commentsCount for postId={} by {}: {}", postId, delta, e.getMessage());
        }
    }

    private GroupPostCommentDto toDto(GroupPostComment c) {
        GroupPostCommentDto d = new GroupPostCommentDto();
        d.setId(c.getId());
        d.setPostId(c.getPostId());
        d.setAuthor(c.getAuthor());
        d.setContent(c.getContent());
        d.setTimestamp(c.getTimestamp());
        d.setUpdatedAt(c.getUpdatedAt());
        d.setEditedAt(c.getEditedAt());
        d.setEdited(c.getEditedAt() != null);
        return d;
    }

    private GroupPostCommentDto toDto(GroupPostComment c, Map<String, UserInfo> userByEmail) {
        GroupPostCommentDto d = toDto(c);
        if (c.getAuthor() != null) {
            UserInfo u = userByEmail.get(c.getAuthor());
            if (u != null) {
                d.setAuthorFirstName(u.getUserFirstName());
                d.setAuthorLastName(u.getUserLastName());
                d.setAuthorProfileImageURL(u.getProfileImageURL());
            }
        }
        return d;
    }

    private void enrichAuthor(GroupPostCommentDto d) {
        if (d == null || d.getAuthor() == null) return;
        userInfoRepo.findByUserEmail(d.getAuthor()).ifPresent(u -> {
            d.setAuthorFirstName(u.getUserFirstName());
            d.setAuthorLastName(u.getUserLastName());
            d.setAuthorProfileImageURL(u.getProfileImageURL());
        });
    }

    /**
     * Notify the post author when someone else comments on their post.
     */
    private void notifyPostAuthorOnNewComment(GroupPostComment savedComment, GroupPostCommentDto enrichedDto) {
        Long postId = savedComment.getPostId();
        if (postId == null) return;

        Optional<GroupPost> postOpt = postRepo.findById(postId);
        if (postOpt.isEmpty()) return;

        GroupPost post = postOpt.get();
        String postAuthorEmail = post.getAuthor();
        if (postAuthorEmail == null) return;

        // Don't notify the author about their own comment
        if (savedComment.getAuthor() != null &&
                savedComment.getAuthor().equalsIgnoreCase(postAuthorEmail)) {
            return;
        }

        Optional<UserInfo> ownerOpt = userInfoRepo.findByUserEmail(postAuthorEmail);
        if (ownerOpt.isEmpty()) return;

        UserInfo owner = ownerOpt.get();

        String commenterName = enrichedDto.getAuthorFirstName() != null
                ? enrichedDto.getAuthorFirstName()
                : "Someone";

        String title = "New comment in your group";
        if (post.getGroupName() != null && !post.getGroupName().isBlank()) {
            title = "New comment in " + post.getGroupName();
        }

        String body = commenterName + " commented: " + snippet(enrichedDto.getContent());
        String iconUrl = enrichedDto.getAuthorProfileImageURL();

        // Deep-link to the post. Service worker already prefers targetUrl first.
        String targetUrl = "/Linked/lg/4D-FwtX/" + post.getGroupId() + "?postId=" + post.getId();

        notificationService.deliverPresenceAware(
                owner.getUserEmail(),
                title,
                body,
                commenterName,
                iconUrl,
                "comment_on_post",
                String.valueOf(post.getId()),
                targetUrl,
                null,
                owner.getFcmtoken()
        );
    }

    private String snippet(String content) {
        if (content == null) return "";
        String trimmed = content.trim();
        if (trimmed.length() <= 80) return trimmed;
        return trimmed.substring(0, 77) + "...";
    }
}