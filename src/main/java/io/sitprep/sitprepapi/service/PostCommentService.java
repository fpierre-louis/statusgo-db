package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.PostComment;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.EmojiReactionDto;
import io.sitprep.sitprepapi.dto.PostCommentDto;
import io.sitprep.sitprepapi.repo.PostCommentRepo;
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
 * Comments on tasks (community-feed posts). Mirrors {@link GroupPostCommentService}
 * exactly so the eventual GroupPost/Post entity merge collapses both surfaces
 * with no semantic drift.
 *
 * <ul>
 *   <li>Creates / updates / deletes comments</li>
 *   <li>Broadcasts EXACTLY ONCE on commit (afterCommit synchronization)</li>
 *   <li>Enriches author profile fields (name, avatar) so the FE renders
 *       without a separate batch profile lookup</li>
 *   <li>Fires a presence-aware notification to the task author when someone
 *       else comments on their post</li>
 * </ul>
 *
 * <p>Replies use the quote-prefix content convention from {@code PostComments}
 * ({@code "> Replying to {name}:\n> {snippet}\n\n{content}"}). No
 * {@code parentCommentId} column — see {@link PostComment} class doc.</p>
 */
@Service
public class PostCommentService {

    private static final Logger log = LoggerFactory.getLogger(PostCommentService.class);

    private final PostCommentRepo commentRepo;
    private final PostRepo taskRepo;
    private final UserInfoRepo userInfoRepo;
    private final WebSocketMessageSender ws;
    private final NotificationService notificationService;
    private final PostCommentReactionService reactionService;

    public PostCommentService(PostCommentRepo commentRepo,
                              PostRepo taskRepo,
                              UserInfoRepo userInfoRepo,
                              WebSocketMessageSender ws,
                              NotificationService notificationService,
                              PostCommentReactionService reactionService) {
        this.commentRepo = commentRepo;
        this.taskRepo = taskRepo;
        this.userInfoRepo = userInfoRepo;
        this.ws = ws;
        this.notificationService = notificationService;
        this.reactionService = reactionService;
    }

    // --------------------------------------------------------------------------------------------
    // Create
    // --------------------------------------------------------------------------------------------
    @Transactional
    public PostCommentDto createCommentFromDto(PostCommentDto dto) {
        if (dto == null) throw new IllegalArgumentException("PostCommentDto is null");
        if (dto.getPostId() == null) throw new IllegalArgumentException("postId is required");
        if (dto.getContent() == null || dto.getContent().trim().isEmpty()) {
            throw new IllegalArgumentException("content is required");
        }
        if (dto.getAuthor() == null || dto.getAuthor().trim().isEmpty()) {
            // Match GroupPostCommentService's lenient default — the resource layer
            // overwrites with the verified token email anyway, so this is a
            // belt-and-suspenders for non-resource paths.
            dto.setAuthor("anonymous@sitprep");
        }

        PostComment c = new PostComment();
        c.setPostId(dto.getPostId());
        c.setAuthor(dto.getAuthor().trim());
        c.setContent(dto.getContent());
        // @CreatedDate / @LastModifiedDate auditing populates timestamp/updatedAt

        PostComment saved = commentRepo.save(c);

        PostCommentDto out = toDto(saved);
        out.setTempId(dto.getTempId()); // preserve optimistic correlation
        enrichAuthor(out);

        // Broadcast AFTER COMMIT to avoid duplicates on rollback
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    ws.sendNewPostComment(saved.getPostId(), out);
                } catch (Exception e) {
                    log.error("WS broadcast failed for new task comment id={}", saved.getId(), e);
                }
                try {
                    notifyPostAuthorOnNewComment(saved, out);
                } catch (Exception e) {
                    log.error("Notification fan-out failed for new task comment id={}", saved.getId(), e);
                }
            }
        });

        return out;
    }

    // --------------------------------------------------------------------------------------------
    // Update
    // --------------------------------------------------------------------------------------------
    @Transactional
    public PostCommentDto updateCommentFromDto(PostCommentDto dto) {
        if (dto == null) throw new IllegalArgumentException("PostCommentDto is null");
        if (dto.getId() == null) throw new IllegalArgumentException("id is required for update");

        PostComment existing = commentRepo.findById(dto.getId())
                .orElseThrow(() -> new IllegalArgumentException("PostComment not found: " + dto.getId()));

        // Ownership check — same MVP shape as GroupPostCommentService.
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

        PostComment saved = commentRepo.save(existing);

        PostCommentDto out = toDto(saved);
        out.setTempId(dto.getTempId());
        out.setEdited(true);
        enrichAuthor(out);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    // Reuse the same topic for create + edit deltas.
                    ws.sendNewPostComment(saved.getPostId(), out);
                } catch (Exception e) {
                    log.error("WS broadcast failed for edit task comment id={}", saved.getId(), e);
                }
            }
        });

        return out;
    }

    // --------------------------------------------------------------------------------------------
    // Delete (REST path: look up postId from the comment)
    // --------------------------------------------------------------------------------------------
    @Transactional
    public void deleteCommentByIdAndBroadcast(Long id) {
        if (id == null) return;
        PostComment c = commentRepo.findById(id).orElse(null);
        if (c == null) return;

        Long postId = c.getPostId();
        commentRepo.deleteById(id);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    ws.sendPostCommentDeletion(postId, id);
                } catch (Exception e) {
                    log.error("WS broadcast failed for delete task comment id={}", id, e);
                }
            }
        });
    }

    // --------------------------------------------------------------------------------------------
    // Queries
    // --------------------------------------------------------------------------------------------
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<PostCommentDto> getCommentsByPostId(Long postId, String viewerEmail) {
        if (postId == null) return List.of();

        List<PostComment> rows = commentRepo.findByPostIdOrderByTimestampAsc(postId);
        if (rows.isEmpty()) return List.of();

        Set<String> emails = rows.stream()
                .map(PostComment::getAuthor).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, UserInfo> userByEmail = userInfoRepo.findByUserEmailIn(new ArrayList<>(emails))
                .stream().collect(Collectors.toMap(UserInfo::getUserEmail, Function.identity()));

        List<PostCommentDto> dtos = rows.stream()
                .map(c -> toDto(c, userByEmail))
                .collect(Collectors.toList());
        return withReactions(dtos, viewerEmail);
    }

    /** Back-compat overload — viewerThanked stays false for callers that don't pass identity. */
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<PostCommentDto> getCommentsByPostId(Long postId) {
        return getCommentsByPostId(postId, null);
    }

    /**
     * Cursor-paginated thread fetch — returns the most-recent {@code limit}
     * comments before {@code beforeId} (or the most-recent {@code limit}
     * if {@code beforeId} is null). Result is in chronological order
     * (oldest → newest within the page) so the FE can append it to the
     * top of its existing list when scrolling up.
     *
     * <p>Reactions + author profiles + thank counts are folded in via
     * the same batched path as {@link #getCommentsByPostId(Long, String)}
     * — one extra DB roundtrip total per page, not N.</p>
     *
     * <p>Caller passes {@code limit} between 1 and {@link #MAX_PAGE_SIZE}
     * inclusive; values outside that range get clamped (no error).</p>
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<PostCommentDto> getCommentsPage(
            Long postId, String viewerEmail, Long beforeId, int limit) {
        if (postId == null) return List.of();
        int safeLimit = clampLimit(limit);
        org.springframework.data.domain.Pageable page =
                org.springframework.data.domain.PageRequest.of(0, safeLimit);

        List<PostComment> rows = (beforeId == null)
                ? commentRepo.findByPostIdOrderByIdDesc(postId, page)
                : commentRepo.findByPostIdAndIdLessThanOrderByIdDesc(postId, beforeId, page);
        if (rows.isEmpty()) return List.of();

        // Reverse to chronological for FE consumption (oldest within the
        // page first; the FE prepends pages above its existing list).
        java.util.Collections.reverse(rows);

        Set<String> emails = rows.stream()
                .map(PostComment::getAuthor).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, UserInfo> userByEmail = userInfoRepo.findByUserEmailIn(new ArrayList<>(emails))
                .stream().collect(Collectors.toMap(UserInfo::getUserEmail, Function.identity()));

        List<PostCommentDto> dtos = rows.stream()
                .map(c -> toDto(c, userByEmail))
                .collect(Collectors.toList());
        return withReactions(dtos, viewerEmail);
    }

    private static final int DEFAULT_PAGE_SIZE = 30;
    private static final int MAX_PAGE_SIZE = 100;
    private static int clampLimit(int limit) {
        if (limit < 1) return DEFAULT_PAGE_SIZE;
        return Math.min(limit, MAX_PAGE_SIZE);
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<PostCommentDto> getCommentsSince(Long postId, Instant since, String viewerEmail) {
        if (postId == null || since == null) return List.of();

        List<PostComment> rows = commentRepo.findByPostIdAndUpdatedAtAfterOrderByUpdatedAtAsc(postId, since);
        if (rows.isEmpty()) return List.of();

        Set<String> emails = rows.stream()
                .map(PostComment::getAuthor).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, UserInfo> userByEmail = userInfoRepo.findByUserEmailIn(new ArrayList<>(emails))
                .stream().collect(Collectors.toMap(UserInfo::getUserEmail, Function.identity()));

        List<PostCommentDto> dtos = rows.stream()
                .map(c -> toDto(c, userByEmail))
                .collect(Collectors.toList());
        return withReactions(dtos, viewerEmail);
    }

    /** Back-compat overload. */
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<PostCommentDto> getCommentsSince(Long postId, Instant since) {
        return getCommentsSince(postId, since, null);
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<PostCommentDto> getCommentById(Long id) {
        if (id == null) return Optional.empty();
        return commentRepo.findById(id).map(c -> {
            PostCommentDto d = toDto(c);
            enrichAuthor(d);
            // Single-comment fold — small, no batching benefit. viewerThanked
            // stays false here (caller is the resource's ownership-check
            // path, not a render path).
            d.setReactions(reactionService.loadByPostCommentId(d.getId()));
            return d;
        });
    }

    /**
     * Batch-fold per-emoji roster + heart "Thank" count + viewer-thanked
     * flag onto a list of PostCommentDtos. Single batched roster query
     * + single batched thank-summary query for the whole thread, so a
     * thread page is two extra DB round trips total, not 2N.
     *
     * <p>{@code viewerEmail} null means an unauthenticated read or a
     * read where viewer identity isn't available; {@code viewerThanked}
     * defaults to false everywhere. Counts + roster always populate.</p>
     */
    private List<PostCommentDto> withReactions(List<PostCommentDto> dtos, String viewerEmail) {
        if (dtos == null || dtos.isEmpty()) return dtos;
        List<Long> ids = dtos.stream()
                .map(PostCommentDto::getId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) return dtos;
        Map<Long, Map<String, List<EmojiReactionDto>>> rosterByComment =
                reactionService.loadByPostCommentIds(ids);
        PostCommentReactionService.ThankSummary summary =
                reactionService.loadThankSummary(ids, viewerEmail);
        for (PostCommentDto d : dtos) {
            if (d.getId() == null) continue;
            d.setReactions(rosterByComment.getOrDefault(d.getId(), Map.of()));
            d.setThanksCount(summary.countFor(d.getId()));
            d.setViewerThanked(summary.viewerThankedComment(d.getId()));
        }
        return dtos;
    }

    /**
     * Per-task comment count for a list of task ids. Used by
     * {@code PostService.withEngagement} to fold {@code commentsCount}
     * onto every {@code PostDto} in one query rather than N. Tasks with no
     * comments are simply absent from the returned map; callers default to
     * 0 for missing keys.
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public Map<Long, Integer> loadCountsByPostIds(Collection<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) return Map.of();
        List<Object[]> rows = commentRepo.countByPostIdIn(postIds);
        Map<Long, Integer> out = new HashMap<>(rows.size());
        for (Object[] row : rows) {
            if (row == null || row.length < 2) continue;
            Long postId = (Long) row[0];
            Long count = (Long) row[1];
            if (postId != null && count != null) {
                out.put(postId, count.intValue());
            }
        }
        return out;
    }

    // --------------------------------------------------------------------------------------------
    // Internals
    // --------------------------------------------------------------------------------------------
    private PostCommentDto toDto(PostComment c) {
        PostCommentDto d = new PostCommentDto();
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

    private PostCommentDto toDto(PostComment c, Map<String, UserInfo> userByEmail) {
        PostCommentDto d = toDto(c);
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

    private void enrichAuthor(PostCommentDto d) {
        if (d == null || d.getAuthor() == null) return;
        userInfoRepo.findByUserEmail(d.getAuthor()).ifPresent(u -> {
            d.setAuthorFirstName(u.getUserFirstName());
            d.setAuthorLastName(u.getUserLastName());
            d.setAuthorProfileImageURL(u.getProfileImageURL());
        });
    }

    /**
     * Notify the task author when someone else comments on their post.
     * Mirrors the post-comment notification but routes to the community
     * task detail page rather than the group post page.
     */
    private void notifyPostAuthorOnNewComment(PostComment savedComment, PostCommentDto enrichedDto) {
        Long postId = savedComment.getPostId();
        if (postId == null) return;

        Optional<Post> taskOpt = taskRepo.findById(postId);
        if (taskOpt.isEmpty()) return;

        Post task = taskOpt.get();
        String taskAuthorEmail = task.getRequesterEmail();
        if (taskAuthorEmail == null) return;

        // Don't notify the author about their own comment.
        if (savedComment.getAuthor() != null &&
                savedComment.getAuthor().equalsIgnoreCase(taskAuthorEmail)) {
            return;
        }

        Optional<UserInfo> ownerOpt = userInfoRepo.findByUserEmail(taskAuthorEmail);
        if (ownerOpt.isEmpty()) return;

        UserInfo owner = ownerOpt.get();

        String commenterName = enrichedDto.getAuthorFirstName() != null
                ? enrichedDto.getAuthorFirstName()
                : "Someone";

        // Title leans on the task title when the kind has one (ask, marketplace,
        // etc.); for body-only kinds (post, tip) where title is null, we just
        // say "your post" so the surface stays generic.
        String title = (task.getTitle() != null && !task.getTitle().isBlank())
                ? "New comment on \"" + snippet(task.getTitle(), 60) + "\""
                : "New comment on your post";

        String body = commenterName + " commented: " + snippet(enrichedDto.getContent(), 80);
        String iconUrl = enrichedDto.getAuthorProfileImageURL();
        String targetUrl = "/community/tasks/" + task.getId();

        notificationService.deliverPresenceAware(
                owner.getUserEmail(),
                title,
                body,
                commenterName,
                iconUrl,
                "comment_on_task",
                String.valueOf(task.getId()),
                targetUrl,
                null,
                owner.getFcmtoken()
        );
    }

    private String snippet(String content, int maxLen) {
        if (content == null) return "";
        String trimmed = content.trim();
        if (trimmed.length() <= maxLen) return trimmed;
        return trimmed.substring(0, Math.max(0, maxLen - 3)) + "...";
    }
}
