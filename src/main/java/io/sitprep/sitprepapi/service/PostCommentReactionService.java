package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.PostComment;
import io.sitprep.sitprepapi.domain.PostCommentReaction;
import io.sitprep.sitprepapi.dto.EmojiReactionDto;
import io.sitprep.sitprepapi.dto.PostCommentReactionFrame;
import io.sitprep.sitprepapi.repo.PostCommentReactionRepo;
import io.sitprep.sitprepapi.repo.PostCommentRepo;
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
 * Add / remove / load emoji reactions on community feed comments
 * ({@link PostComment}). Mirrors {@link PostReactionService} (the
 * post-level reaction service) modulo the FK target.
 *
 * <p>Persists to the {@code post_comment_reaction} table; broadcasts a
 * {@link PostCommentReactionFrame} on the parent post's comment topic
 * ({@code /topic/post-comments/{postId}}) after commit so other viewers
 * subscribed to the thread update live.</p>
 *
 * <p>Default heart "Thank" emoji is {@code "❤"} (single source of
 * truth shared with {@link PostReactionService#THANK_EMOJI} so the FE
 * can compare against one constant). Surface supports any short
 * emoji string for forward-compat with a multi-emoji picker.</p>
 */
@Service
public class PostCommentReactionService {

    /** Hard cap on emoji length — matches column length on PostCommentReaction.emoji. */
    private static final int MAX_EMOJI_LENGTH = 32;

    /** Default emoji used by the heart "Thank" UI affordance on comment bubbles. */
    public static final String THANK_EMOJI = "❤";

    private final PostCommentReactionRepo reactionRepo;
    private final PostCommentRepo commentRepo;
    private final WebSocketMessageSender ws;

    public PostCommentReactionService(PostCommentReactionRepo reactionRepo,
                                      PostCommentRepo commentRepo,
                                      WebSocketMessageSender ws) {
        this.reactionRepo = reactionRepo;
        this.commentRepo = commentRepo;
        this.ws = ws;
    }

    @Transactional
    public Map<String, List<EmojiReactionDto>> add(Long postCommentId, String userEmail, String emoji) {
        String normalizedEmoji = sanitizeEmoji(emoji);
        String normalizedEmail = normalizeEmail(userEmail);
        PostComment comment = loadCommentOr404(postCommentId);

        Optional<PostCommentReaction> existing = reactionRepo
                .findByPostCommentIdAndUserEmailIgnoreCaseAndEmoji(
                        postCommentId, normalizedEmail, normalizedEmoji);

        Instant at;
        if (existing.isPresent()) {
            // Idempotent: re-adding the same reaction doesn't duplicate or
            // bump addedAt — just returns the current roster.
            at = existing.get().getAddedAt();
        } else {
            PostCommentReaction r = new PostCommentReaction();
            r.setPostCommentId(postCommentId);
            r.setUserEmail(normalizedEmail);
            r.setEmoji(normalizedEmoji);
            reactionRepo.save(r);
            at = r.getAddedAt();
            broadcastAfterCommit(PostCommentReactionFrame.add(
                    postCommentId, comment.getPostId(),
                    normalizedEmoji, normalizedEmail, at));
        }

        return loadByPostCommentId(postCommentId);
    }

    @Transactional
    public Map<String, List<EmojiReactionDto>> remove(Long postCommentId, String userEmail, String emoji) {
        String normalizedEmoji = sanitizeEmoji(emoji);
        String normalizedEmail = normalizeEmail(userEmail);
        PostComment comment = loadCommentOr404(postCommentId);

        int deleted = reactionRepo.deleteByPostCommentUserEmoji(
                postCommentId, normalizedEmail, normalizedEmoji);
        if (deleted > 0) {
            broadcastAfterCommit(PostCommentReactionFrame.remove(
                    postCommentId, comment.getPostId(),
                    normalizedEmoji, normalizedEmail, Instant.now()));
        }
        return loadByPostCommentId(postCommentId);
    }

    /** Single-comment load used by the resource GET and per-comment DTO build. */
    public Map<String, List<EmojiReactionDto>> loadByPostCommentId(Long postCommentId) {
        return groupByEmoji(reactionRepo.findByPostCommentId(postCommentId));
    }

    /**
     * Batched roster fetch — one repo call for a whole thread. Returns a
     * map keyed by post-comment id; comments without reactions are absent.
     */
    public Map<Long, Map<String, List<EmojiReactionDto>>> loadByPostCommentIds(
            Collection<Long> postCommentIds) {
        if (postCommentIds == null || postCommentIds.isEmpty()) return Collections.emptyMap();
        List<PostCommentReaction> rows = reactionRepo.findByPostCommentIdIn(postCommentIds);
        if (rows.isEmpty()) return Collections.emptyMap();

        Map<Long, List<PostCommentReaction>> byComment = rows.stream()
                .collect(Collectors.groupingBy(PostCommentReaction::getPostCommentId));
        Map<Long, Map<String, List<EmojiReactionDto>>> out = new HashMap<>(byComment.size());
        byComment.forEach((commentId, list) -> out.put(commentId, groupByEmoji(list)));
        return out;
    }

    /**
     * Heart-thank counts + viewer-thanked set in one cheap query each.
     * Used by {@code PostCommentService} when shaping a thread listing —
     * populates {@code thanksCount} and {@code viewerThanked} on every
     * PostCommentDto so the FE doesn't need to fetch reactions
     * separately for each bubble.
     */
    public ThankSummary loadThankSummary(Collection<Long> postCommentIds, String viewerEmail) {
        if (postCommentIds == null || postCommentIds.isEmpty()) {
            return new ThankSummary(Map.of(), Set.of());
        }
        List<PostCommentReaction> rows = reactionRepo.findByPostCommentIdIn(postCommentIds);
        Map<Long, Integer> counts = new HashMap<>();
        for (PostCommentReaction r : rows) {
            if (THANK_EMOJI.equals(r.getEmoji())) {
                counts.merge(r.getPostCommentId(), 1, Integer::sum);
            }
        }
        Set<Long> viewerThanked;
        if (viewerEmail == null || viewerEmail.isBlank()) {
            viewerThanked = Set.of();
        } else {
            viewerThanked = new HashSet<>(reactionRepo.findPostCommentIdsWhereViewerReacted(
                    postCommentIds, viewerEmail.trim().toLowerCase(Locale.ROOT), THANK_EMOJI));
        }
        return new ThankSummary(counts, viewerThanked);
    }

    /** Bundle returned by {@link #loadThankSummary}. */
    public record ThankSummary(Map<Long, Integer> counts, Set<Long> viewerThanked) {
        public int countFor(Long postCommentId) {
            Integer c = counts.get(postCommentId);
            return c == null ? 0 : c;
        }
        public boolean viewerThankedComment(Long postCommentId) {
            return viewerThanked.contains(postCommentId);
        }
    }

    // ------------------------------------------------------------------

    private Map<String, List<EmojiReactionDto>> groupByEmoji(List<PostCommentReaction> rows) {
        if (rows == null || rows.isEmpty()) return new LinkedHashMap<>();
        rows.sort(Comparator.comparing(PostCommentReaction::getAddedAt));
        Map<String, List<EmojiReactionDto>> out = new LinkedHashMap<>();
        for (PostCommentReaction r : rows) {
            out.computeIfAbsent(r.getEmoji(), k -> new ArrayList<>())
                    .add(new EmojiReactionDto(r.getUserEmail(), r.getAddedAt()));
        }
        return out;
    }

    private PostComment loadCommentOr404(Long postCommentId) {
        return commentRepo.findById(postCommentId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "PostComment not found"));
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

    private void broadcastAfterCommit(PostCommentReactionFrame frame) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { ws.sendPostCommentReaction(frame); }
            });
        } else {
            ws.sendPostCommentReaction(frame);
        }
    }
}
