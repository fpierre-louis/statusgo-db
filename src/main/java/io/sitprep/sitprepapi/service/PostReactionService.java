package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.PostReaction;
import io.sitprep.sitprepapi.dto.EmojiReactionDto;
import io.sitprep.sitprepapi.dto.PostReactionFrame;
import io.sitprep.sitprepapi.repo.PostReactionRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
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
 * Add / remove / load emoji reactions on tasks (community-feed posts).
 * Mirrors {@code GroupPostReactionService} so the eventual GroupPost/Post entity
 * merge collapses both into one — same broadcast convention, same idempotent
 * add-or-no-op semantics, same {@link EmojiReactionDto} reuse for the roster
 * shape returned to the FE.
 *
 * <p>Persists to the {@code task_reaction} table; broadcasts a
 * {@link PostReactionFrame} on the task's own topic
 * ({@code /topic/group/{groupId}/tasks} or
 * {@code /topic/community/tasks/{zipBucket}}) after commit so other viewers
 * update live.</p>
 *
 * <p>The community-feed "Thank" affordance toggles emoji {@code "❤"};
 * the service supports any short emoji string so future surfaces can
 * extend without schema work.</p>
 */
@Service
public class PostReactionService {

    /** Hard cap on emoji length — matches column length on PostReaction.emoji. */
    private static final int MAX_EMOJI_LENGTH = 32;

    /** Default emoji used by the heart "Thank" UI affordance on feed cards. */
    public static final String THANK_EMOJI = "❤";

    private final PostReactionRepo reactionRepo;
    private final PostRepo taskRepo;
    private final WebSocketMessageSender ws;

    public PostReactionService(PostReactionRepo reactionRepo,
                               PostRepo taskRepo,
                               WebSocketMessageSender ws) {
        this.reactionRepo = reactionRepo;
        this.taskRepo = taskRepo;
        this.ws = ws;
    }

    @Transactional
    public Map<String, List<EmojiReactionDto>> add(Long postId, String userEmail, String emoji) {
        String normalizedEmoji = sanitizeEmoji(emoji);
        String normalizedEmail = normalizeEmail(userEmail);
        Post task = loadPostOr404(postId);

        Optional<PostReaction> existing = reactionRepo
                .findByPostIdAndUserEmailIgnoreCaseAndEmoji(postId, normalizedEmail, normalizedEmoji);

        Instant at;
        if (existing.isPresent()) {
            // Idempotent: re-adding the same reaction doesn't duplicate or
            // bump addedAt — just returns the current roster.
            at = existing.get().getAddedAt();
        } else {
            PostReaction r = new PostReaction();
            r.setPostId(postId);
            r.setUserEmail(normalizedEmail);
            r.setEmoji(normalizedEmoji);
            reactionRepo.save(r);
            at = r.getAddedAt();
            broadcastAfterCommit(PostReactionFrame.add(
                    postId, task.getGroupId(), task.getZipBucket(),
                    normalizedEmoji, normalizedEmail, at));
        }

        return loadByPostId(postId);
    }

    @Transactional
    public Map<String, List<EmojiReactionDto>> remove(Long postId, String userEmail, String emoji) {
        String normalizedEmoji = sanitizeEmoji(emoji);
        String normalizedEmail = normalizeEmail(userEmail);
        Post task = loadPostOr404(postId);

        int deleted = reactionRepo.deleteByPostUserEmoji(postId, normalizedEmail, normalizedEmoji);
        if (deleted > 0) {
            broadcastAfterCommit(PostReactionFrame.remove(
                    postId, task.getGroupId(), task.getZipBucket(),
                    normalizedEmoji, normalizedEmail, Instant.now()));
        }
        return loadByPostId(postId);
    }

    /** Single-task load used by the resource GET and by the per-task DTO build. */
    public Map<String, List<EmojiReactionDto>> loadByPostId(Long postId) {
        return groupByEmoji(reactionRepo.findByPostId(postId));
    }

    /**
     * Batched roster fetch — one repo call for a whole listing. Returns a
     * map keyed by task id; tasks without reactions are simply absent so
     * callers don't need null-checks for empty rosters.
     */
    public Map<Long, Map<String, List<EmojiReactionDto>>> loadByPostIds(Collection<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) return Collections.emptyMap();
        List<PostReaction> rows = reactionRepo.findByPostIdIn(postIds);
        if (rows.isEmpty()) return Collections.emptyMap();

        Map<Long, List<PostReaction>> byTask = rows.stream()
                .collect(Collectors.groupingBy(PostReaction::getPostId));
        Map<Long, Map<String, List<EmojiReactionDto>>> out = new HashMap<>(byTask.size());
        byTask.forEach((postId, list) -> out.put(postId, groupByEmoji(list)));
        return out;
    }

    /**
     * Heart-thank counts + viewer-thanked set in one cheap query each.
     * Used by {@code PostService} when shaping a feed listing — populates
     * {@code thanksCount} and {@code viewerThanked} on every PostDto so
     * the FE doesn't need to fetch reactions separately for each card.
     *
     * <p>Returns a {@link ThankSummary} bundle so the listing path can
     * destructure once instead of running two batch queries.</p>
     */
    public ThankSummary loadThankSummary(Collection<Long> postIds, String viewerEmail) {
        if (postIds == null || postIds.isEmpty()) {
            return new ThankSummary(Map.of(), Set.of());
        }
        List<PostReaction> rows = reactionRepo.findByPostIdIn(postIds);
        Map<Long, Integer> counts = new HashMap<>();
        for (PostReaction r : rows) {
            if (THANK_EMOJI.equals(r.getEmoji())) {
                counts.merge(r.getPostId(), 1, Integer::sum);
            }
        }
        Set<Long> viewerThanked;
        if (viewerEmail == null || viewerEmail.isBlank()) {
            viewerThanked = Set.of();
        } else {
            viewerThanked = new HashSet<>(reactionRepo.findPostIdsWhereViewerReacted(
                    postIds, viewerEmail.trim().toLowerCase(Locale.ROOT), THANK_EMOJI));
        }
        return new ThankSummary(counts, viewerThanked);
    }

    /** Bundle returned by {@link #loadThankSummary}. */
    public record ThankSummary(Map<Long, Integer> counts, Set<Long> viewerThanked) {
        public int countFor(Long postId) {
            Integer c = counts.get(postId);
            return c == null ? 0 : c;
        }
        public boolean viewerThankedTask(Long postId) {
            return viewerThanked.contains(postId);
        }
    }

    /**
     * Per-emoji breakdown for a batch of posts (used by the feed list
     * path so the FE can render `❤ 12 · 🙏 4 · 👍 2` clusters under
     * each post). Returns:
     * <ul>
     *   <li>{@code countsByPost} — Map&lt;postId, Map&lt;emoji,
     *       count&gt;&gt;</li>
     *   <li>{@code viewerEmojisByPost} — Map&lt;postId,
     *       Set&lt;emoji&gt;&gt; — the emojis this viewer recorded on
     *       each post; lets the FE highlight the viewer's chosen
     *       emoji in the picker.</li>
     * </ul>
     *
     * <p>Reuses the same {@code findByPostIdIn} batch query so this
     * costs one extra hash-bucket pass over rows already in memory
     * — no additional DB hit beyond what {@link #loadThankSummary}
     * was already doing. In fact callers can pick one OR the other;
     * the legacy thank-only path stays for code that hasn't migrated
     * to the per-emoji DTO yet.</p>
     */
    public ReactionSummary loadReactionSummary(Collection<Long> postIds, String viewerEmail) {
        if (postIds == null || postIds.isEmpty()) {
            return new ReactionSummary(Map.of(), Map.of());
        }
        List<PostReaction> rows = reactionRepo.findByPostIdIn(postIds);
        if (rows.isEmpty()) {
            return new ReactionSummary(Map.of(), Map.of());
        }
        String viewerNormalized = (viewerEmail == null || viewerEmail.isBlank())
                ? null
                : viewerEmail.trim().toLowerCase(Locale.ROOT);

        Map<Long, Map<String, Integer>> countsByPost = new HashMap<>();
        Map<Long, Set<String>> viewerEmojisByPost = new HashMap<>();
        for (PostReaction r : rows) {
            countsByPost
                    .computeIfAbsent(r.getPostId(), k -> new LinkedHashMap<>())
                    .merge(r.getEmoji(), 1, Integer::sum);
            if (viewerNormalized != null
                    && viewerNormalized.equalsIgnoreCase(r.getUserEmail())) {
                viewerEmojisByPost
                        .computeIfAbsent(r.getPostId(), k -> new HashSet<>())
                        .add(r.getEmoji());
            }
        }
        return new ReactionSummary(countsByPost, viewerEmojisByPost);
    }

    /** Bundle returned by {@link #loadReactionSummary}. */
    public record ReactionSummary(
            Map<Long, Map<String, Integer>> countsByPost,
            Map<Long, Set<String>> viewerEmojisByPost
    ) {
        public Map<String, Integer> countsFor(Long postId) {
            Map<String, Integer> c = countsByPost.get(postId);
            return c == null ? Map.of() : c;
        }
        public Set<String> viewerEmojisFor(Long postId) {
            Set<String> s = viewerEmojisByPost.get(postId);
            return s == null ? Set.of() : s;
        }
    }

    // ------------------------------------------------------------------

    private Map<String, List<EmojiReactionDto>> groupByEmoji(List<PostReaction> rows) {
        if (rows == null || rows.isEmpty()) return new LinkedHashMap<>();
        rows.sort(Comparator.comparing(PostReaction::getAddedAt));
        Map<String, List<EmojiReactionDto>> out = new LinkedHashMap<>();
        for (PostReaction r : rows) {
            out.computeIfAbsent(r.getEmoji(), k -> new ArrayList<>())
                    .add(new EmojiReactionDto(r.getUserEmail(), r.getAddedAt()));
        }
        return out;
    }

    private Post loadPostOr404(Long postId) {
        return taskRepo.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));
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

    private void broadcastAfterCommit(PostReactionFrame frame) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { ws.sendPostReaction(frame); }
            });
        } else {
            ws.sendPostReaction(frame);
        }
    }
}
