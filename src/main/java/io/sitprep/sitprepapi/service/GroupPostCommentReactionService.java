package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.GroupPostComment;
import io.sitprep.sitprepapi.domain.GroupPostCommentReaction;
import io.sitprep.sitprepapi.dto.EmojiReactionDto;
import io.sitprep.sitprepapi.dto.GroupPostCommentReactionFrame;
import io.sitprep.sitprepapi.repo.GroupPostCommentReactionRepo;
import io.sitprep.sitprepapi.repo.GroupPostCommentRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Add / remove / load emoji reactions on group chat comments
 * ({@link GroupPostComment}). Mirrors {@link PostCommentReactionService}
 * (community-feed comment reactions) modulo the FK target + topic.
 *
 * <p>Persists to the {@code group_post_comment_reaction} table;
 * broadcasts a {@link GroupPostCommentReactionFrame} on the parent
 * post's comment topic ({@code /topic/group-post-comments/{postId}})
 * after commit so other viewers subscribed to the thread update live.</p>
 *
 * <p>Default heart "Thank" emoji is {@code "❤"} (single source of
 * truth shared with {@link PostCommentReactionService#THANK_EMOJI}).
 * Surface supports any short emoji string for forward-compat.</p>
 */
@Service
public class GroupPostCommentReactionService {

    /** Hard cap on emoji length — matches column length on entity.emoji. */
    private static final int MAX_EMOJI_LENGTH = 32;

    /** Default emoji used by the heart "Thank" UI affordance on chat comment bubbles. */
    public static final String THANK_EMOJI = "❤";

    private final GroupPostCommentReactionRepo reactionRepo;
    private final GroupPostCommentRepo commentRepo;
    private final WebSocketMessageSender ws;

    public GroupPostCommentReactionService(GroupPostCommentReactionRepo reactionRepo,
                                           GroupPostCommentRepo commentRepo,
                                           WebSocketMessageSender ws) {
        this.reactionRepo = reactionRepo;
        this.commentRepo = commentRepo;
        this.ws = ws;
    }

    @Transactional
    public Map<String, List<EmojiReactionDto>> add(Long groupPostCommentId, String userEmail, String emoji) {
        String normalizedEmoji = sanitizeEmoji(emoji);
        String normalizedEmail = normalizeEmail(userEmail);
        GroupPostComment comment = loadCommentOr404(groupPostCommentId);

        Optional<GroupPostCommentReaction> existing = reactionRepo
                .findByGroupPostCommentIdAndUserEmailIgnoreCaseAndEmoji(
                        groupPostCommentId, normalizedEmail, normalizedEmoji);

        Instant at;
        if (existing.isPresent()) {
            // Idempotent: re-adding the same reaction doesn't duplicate or
            // bump addedAt — just returns the current roster.
            at = existing.get().getAddedAt();
        } else {
            GroupPostCommentReaction r = new GroupPostCommentReaction();
            r.setGroupPostCommentId(groupPostCommentId);
            r.setUserEmail(normalizedEmail);
            r.setEmoji(normalizedEmoji);
            reactionRepo.save(r);
            at = r.getAddedAt();
            broadcastAfterCommit(GroupPostCommentReactionFrame.add(
                    groupPostCommentId, comment.getPostId(),
                    normalizedEmoji, normalizedEmail, at));
        }

        return loadByGroupPostCommentId(groupPostCommentId);
    }

    @Transactional
    public Map<String, List<EmojiReactionDto>> remove(Long groupPostCommentId, String userEmail, String emoji) {
        String normalizedEmoji = sanitizeEmoji(emoji);
        String normalizedEmail = normalizeEmail(userEmail);
        GroupPostComment comment = loadCommentOr404(groupPostCommentId);

        int deleted = reactionRepo.deleteByGroupPostCommentUserEmoji(
                groupPostCommentId, normalizedEmail, normalizedEmoji);
        if (deleted > 0) {
            broadcastAfterCommit(GroupPostCommentReactionFrame.remove(
                    groupPostCommentId, comment.getPostId(),
                    normalizedEmoji, normalizedEmail, Instant.now()));
        }
        return loadByGroupPostCommentId(groupPostCommentId);
    }

    /** Single-comment load used by the resource GET and per-comment DTO build. */
    public Map<String, List<EmojiReactionDto>> loadByGroupPostCommentId(Long groupPostCommentId) {
        return groupByEmoji(reactionRepo.findByGroupPostCommentId(groupPostCommentId));
    }

    /**
     * Batched roster fetch — one repo call for a whole thread.
     */
    public Map<Long, Map<String, List<EmojiReactionDto>>> loadByGroupPostCommentIds(
            Collection<Long> groupPostCommentIds) {
        if (groupPostCommentIds == null || groupPostCommentIds.isEmpty()) return Collections.emptyMap();
        List<GroupPostCommentReaction> rows = reactionRepo.findByGroupPostCommentIdIn(groupPostCommentIds);
        if (rows.isEmpty()) return Collections.emptyMap();

        Map<Long, List<GroupPostCommentReaction>> byComment = rows.stream()
                .collect(Collectors.groupingBy(GroupPostCommentReaction::getGroupPostCommentId));
        Map<Long, Map<String, List<EmojiReactionDto>>> out = new HashMap<>(byComment.size());
        byComment.forEach((commentId, list) -> out.put(commentId, groupByEmoji(list)));
        return out;
    }

    /**
     * Heart-thank counts + viewer-thanked set in one cheap query each.
     * Used by {@code GroupPostCommentService} to populate
     * {@code thanksCount} and {@code viewerThanked} on every
     * GroupPostCommentDto in a thread page without per-comment lookups.
     */
    public ThankSummary loadThankSummary(Collection<Long> groupPostCommentIds, String viewerEmail) {
        if (groupPostCommentIds == null || groupPostCommentIds.isEmpty()) {
            return new ThankSummary(Map.of(), Set.of());
        }
        List<GroupPostCommentReaction> rows = reactionRepo.findByGroupPostCommentIdIn(groupPostCommentIds);
        Map<Long, Integer> counts = new HashMap<>();
        for (GroupPostCommentReaction r : rows) {
            if (THANK_EMOJI.equals(r.getEmoji())) {
                counts.merge(r.getGroupPostCommentId(), 1, Integer::sum);
            }
        }
        Set<Long> viewerThanked;
        if (viewerEmail == null || viewerEmail.isBlank()) {
            viewerThanked = Set.of();
        } else {
            viewerThanked = new HashSet<>(reactionRepo.findGroupPostCommentIdsWhereViewerReacted(
                    groupPostCommentIds, viewerEmail.trim().toLowerCase(Locale.ROOT), THANK_EMOJI));
        }
        return new ThankSummary(counts, viewerThanked);
    }

    /** Bundle returned by {@link #loadThankSummary}. */
    public record ThankSummary(Map<Long, Integer> counts, Set<Long> viewerThanked) {
        public int countFor(Long groupPostCommentId) {
            Integer c = counts.get(groupPostCommentId);
            return c == null ? 0 : c;
        }
        public boolean viewerThankedComment(Long groupPostCommentId) {
            return viewerThanked.contains(groupPostCommentId);
        }
    }

    // ------------------------------------------------------------------

    private Map<String, List<EmojiReactionDto>> groupByEmoji(List<GroupPostCommentReaction> rows) {
        if (rows == null || rows.isEmpty()) return new LinkedHashMap<>();
        rows.sort(Comparator.comparing(GroupPostCommentReaction::getAddedAt));
        Map<String, List<EmojiReactionDto>> out = new LinkedHashMap<>();
        for (GroupPostCommentReaction r : rows) {
            out.computeIfAbsent(r.getEmoji(), k -> new ArrayList<>())
                    .add(new EmojiReactionDto(r.getUserEmail(), r.getAddedAt()));
        }
        return out;
    }

    private GroupPostComment loadCommentOr404(Long groupPostCommentId) {
        return commentRepo.findById(groupPostCommentId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "GroupPostComment not found"));
    }

    private static String sanitizeEmoji(String emoji) {
        if (emoji == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "emoji required");
        }
        String trimmed = emoji.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "emoji required");
        }
        if (trimmed.length() > MAX_EMOJI_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "emoji too long");
        }
        return trimmed;
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated request required");
        }
        return email.trim().toLowerCase();
    }

    private void broadcastAfterCommit(GroupPostCommentReactionFrame frame) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { ws.sendGroupPostCommentReaction(frame); }
            });
        } else {
            ws.sendGroupPostCommentReaction(frame);
        }
    }
}
