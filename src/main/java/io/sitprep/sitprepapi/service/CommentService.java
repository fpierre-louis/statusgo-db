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
 */
@Service
public class CommentService {

    private static final Logger log = LoggerFactory.getLogger(CommentService.class);

    private final CommentRepo commentRepo;
    private final PostRepo postRepo;
    private final UserInfoRepo userInfoRepo;
    private final WebSocketMessageSender ws;

    public CommentService(
            CommentRepo commentRepo,
            PostRepo postRepo,
            UserInfoRepo userInfoRepo,
            WebSocketMessageSender ws
    ) {
        this.commentRepo = commentRepo;
        this.postRepo = postRepo;
        this.userInfoRepo = userInfoRepo;
        this.ws = ws;
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
            @Override public void afterCommit() {
                try {
                    ws.sendNewComment(saved.getPostId(), out);
                } catch (Exception e) {
                    log.error("WS broadcast failed for new comment id={}", saved.getId(), e);
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
            @Override public void afterCommit() {
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
            @Override public void afterCommit() {
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
            @Override public void afterCommit() {
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
}