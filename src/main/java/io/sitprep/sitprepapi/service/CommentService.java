package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Comment;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.CommentDto;
import io.sitprep.sitprepapi.repo.CommentRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
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
 * Comment service:
 * - Creates/updates/deletes comments
 * - Broadcasts EXACTLY ONCE after successful commit
 * - Enriches author fields for clients (name, avatar)
 * - Sends presence-aware notifications (e.g., "comment on your post")
 */
@Service
public class CommentService {

    private static final Logger log = LoggerFactory.getLogger(CommentService.class);

    private final CommentRepo commentRepo;
    private final PostRepo postRepo;
    private final UserInfoRepo userInfoRepo;
    private final WebSocketMessageSender ws;
    private final NotificationService notificationService;

    public CommentService(
            CommentRepo commentRepo,
            PostRepo postRepo,
            UserInfoRepo userInfoRepo,
            WebSocketMessageSender ws,
            NotificationService notificationService
    ) {
        this.commentRepo = commentRepo;
        this.postRepo = postRepo;
        this.userInfoRepo = userInfoRepo;
        this.ws = ws;
        this.notificationService = notificationService;
    }

    // --------------------------------------------------------------------------------------------
    // Create (REST or WS)
    // --------------------------------------------------------------------------------------------
    @Transactional
    public CommentDto createCommentFromDto(CommentDto dto) {
        if (dto == null) throw new IllegalArgumentException("CommentDto is null");
        if (dto.getPostId() == null) throw new IllegalArgumentException("postId is required");
        if (dto.getContent() == null || dto.getContent().trim().isEmpty()) {
            throw new IllegalArgumentException("content is required");
        }
        if (dto.getAuthor() == null || dto.getAuthor().trim().isEmpty()) {
            dto.setAuthor("anonymous@sitprep");
        }

        Comment c = new Comment();
        c.setPostId(dto.getPostId());
        c.setAuthor(dto.getAuthor().trim());
        c.setContent(dto.getContent());
        // @CreatedDate/@LastModifiedDate auditing populates timestamp/updatedAt

        Comment saved = commentRepo.save(c);
        bumpCommentsCount(saved.getPostId(), +1);

        CommentDto out = toDto(saved);
        // preserve tempId for optimistic merge on the client
        out.setTempId(dto.getTempId());
        enrichAuthor(out);

        // Broadcast AFTER COMMIT to avoid duplicates on rollback
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    ws.sendNewComment(saved.getPostId(), out);
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
    public CommentDto updateCommentFromDto(CommentDto dto) {
        if (dto == null) throw new IllegalArgumentException("CommentDto is null");
        if (dto.getId() == null) throw new IllegalArgumentException("id is required for update");

        Comment existing = commentRepo.findById(dto.getId())
                .orElseThrow(() -> new IllegalArgumentException("Comment not found: " + dto.getId()));

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

        Comment saved = commentRepo.save(existing);

        CommentDto out = toDto(saved);
        out.setTempId(dto.getTempId()); // keep optimistic correlation if present
        out.setEdited(true);
        enrichAuthor(out);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    // Reuse same topic for create/edit deltas
                    ws.sendNewComment(saved.getPostId(), out);
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
                    ws.sendCommentDeletion(pid, commentId);
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
        Comment c = commentRepo.findById(id).orElse(null);
        if (c == null) return;

        Long postId = c.getPostId();
        commentRepo.deleteById(id);
        bumpCommentsCount(postId, -1);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    ws.sendCommentDeletion(postId, id);
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
    public List<CommentDto> getCommentsByPostId(Long postId) {
        if (postId == null) return List.of();

        List<Comment> rows = commentRepo.findByPostIdOrderByTimestampAsc(postId);
        if (rows.isEmpty()) return List.of();

        Set<String> emails = rows.stream()
                .map(Comment::getAuthor).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, UserInfo> userByEmail = userInfoRepo.findByUserEmailIn(new ArrayList<>(emails))
                .stream().collect(Collectors.toMap(UserInfo::getUserEmail, Function.identity()));

        return rows.stream().map(c -> toDto(c, userByEmail)).collect(Collectors.toList());
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Map<Long, List<CommentDto>> getCommentsForPosts(List<Long> postIds, Integer limitPerPost) {
        if (postIds == null || postIds.isEmpty()) return Map.of();

        List<Comment> rows = commentRepo.findAllByPostIdInOrderByPostIdAscTimestampAsc(postIds);
        if (rows.isEmpty()) return Map.of();

        Map<Long, List<Comment>> byPost = rows.stream()
                .collect(Collectors.groupingBy(Comment::getPostId, LinkedHashMap::new, Collectors.toList()));

        Set<String> emails = rows.stream()
                .map(Comment::getAuthor).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, UserInfo> userByEmail = userInfoRepo.findByUserEmailIn(new ArrayList<>(emails))
                .stream().collect(Collectors.toMap(UserInfo::getUserEmail, Function.identity()));

        Map<Long, List<CommentDto>> out = new LinkedHashMap<>();
        for (var e : byPost.entrySet()) {
            List<Comment> list = e.getValue();
            if (limitPerPost != null && limitPerPost > 0 && list.size() > limitPerPost) {
                list = list.subList(list.size() - limitPerPost, list.size()); // last N
            }
            out.put(e.getKey(), list.stream().map(c -> toDto(c, userByEmail)).collect(Collectors.toList()));
        }
        return out;
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<CommentDto> getCommentsSince(Long postId, Instant since) {
        if (postId == null || since == null) return List.of();

        var rows = commentRepo.findByPostIdAndUpdatedAtAfterOrderByUpdatedAtAsc(postId, since);
        if (rows.isEmpty()) return List.of();

        Set<String> emails = rows.stream()
                .map(Comment::getAuthor).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, UserInfo> userByEmail = userInfoRepo.findByUserEmailIn(new ArrayList<>(emails))
                .stream().collect(Collectors.toMap(UserInfo::getUserEmail, Function.identity()));

        return rows.stream().map(c -> toDto(c, userByEmail)).collect(Collectors.toList());
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<CommentDto> getCommentById(Long id) {
        if (id == null) return Optional.empty();
        return commentRepo.findById(id).map(c -> {
            CommentDto d = toDto(c);
            enrichAuthor(d);
            return d;
        });
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

    private CommentDto toDto(Comment c) {
        CommentDto d = new CommentDto();
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

    private CommentDto toDto(Comment c, Map<String, UserInfo> userByEmail) {
        CommentDto d = toDto(c);
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

    private void enrichAuthor(CommentDto d) {
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
    private void notifyPostAuthorOnNewComment(Comment savedComment, CommentDto enrichedDto) {
        Long postId = savedComment.getPostId();
        if (postId == null) return;

        Optional<Post> postOpt = postRepo.findById(postId);
        if (postOpt.isEmpty()) return;

        Post post = postOpt.get();
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