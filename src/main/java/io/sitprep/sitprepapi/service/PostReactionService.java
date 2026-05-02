package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.PostReaction;
import io.sitprep.sitprepapi.dto.PostReactionDto;
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
 * add-or-no-op semantics, same {@link PostReactionDto} reuse for the roster
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
    public Map<String, List<PostReactionDto>> add(Long taskId, String userEmail, String emoji) {
        String normalizedEmoji = sanitizeEmoji(emoji);
        String normalizedEmail = normalizeEmail(userEmail);
        Post task = loadTaskOr404(taskId);

        Optional<PostReaction> existing = reactionRepo
                .findByTaskIdAndUserEmailIgnoreCaseAndEmoji(taskId, normalizedEmail, normalizedEmoji);

        Instant at;
        if (existing.isPresent()) {
            // Idempotent: re-adding the same reaction doesn't duplicate or
            // bump addedAt — just returns the current roster.
            at = existing.get().getAddedAt();
        } else {
            PostReaction r = new PostReaction();
            r.setTaskId(taskId);
            r.setUserEmail(normalizedEmail);
            r.setEmoji(normalizedEmoji);
            reactionRepo.save(r);
            at = r.getAddedAt();
            broadcastAfterCommit(PostReactionFrame.add(
                    taskId, task.getGroupId(), task.getZipBucket(),
                    normalizedEmoji, normalizedEmail, at));
        }

        return loadByTaskId(taskId);
    }

    @Transactional
    public Map<String, List<PostReactionDto>> remove(Long taskId, String userEmail, String emoji) {
        String normalizedEmoji = sanitizeEmoji(emoji);
        String normalizedEmail = normalizeEmail(userEmail);
        Post task = loadTaskOr404(taskId);

        int deleted = reactionRepo.deleteByTaskUserEmoji(taskId, normalizedEmail, normalizedEmoji);
        if (deleted > 0) {
            broadcastAfterCommit(PostReactionFrame.remove(
                    taskId, task.getGroupId(), task.getZipBucket(),
                    normalizedEmoji, normalizedEmail, Instant.now()));
        }
        return loadByTaskId(taskId);
    }

    /** Single-task load used by the resource GET and by the per-task DTO build. */
    public Map<String, List<PostReactionDto>> loadByTaskId(Long taskId) {
        return groupByEmoji(reactionRepo.findByTaskId(taskId));
    }

    /**
     * Batched roster fetch — one repo call for a whole listing. Returns a
     * map keyed by task id; tasks without reactions are simply absent so
     * callers don't need null-checks for empty rosters.
     */
    public Map<Long, Map<String, List<PostReactionDto>>> loadByTaskIds(Collection<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) return Collections.emptyMap();
        List<PostReaction> rows = reactionRepo.findByTaskIdIn(taskIds);
        if (rows.isEmpty()) return Collections.emptyMap();

        Map<Long, List<PostReaction>> byTask = rows.stream()
                .collect(Collectors.groupingBy(PostReaction::getTaskId));
        Map<Long, Map<String, List<PostReactionDto>>> out = new HashMap<>(byTask.size());
        byTask.forEach((taskId, list) -> out.put(taskId, groupByEmoji(list)));
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
    public ThankSummary loadThankSummary(Collection<Long> taskIds, String viewerEmail) {
        if (taskIds == null || taskIds.isEmpty()) {
            return new ThankSummary(Map.of(), Set.of());
        }
        List<PostReaction> rows = reactionRepo.findByTaskIdIn(taskIds);
        Map<Long, Integer> counts = new HashMap<>();
        for (PostReaction r : rows) {
            if (THANK_EMOJI.equals(r.getEmoji())) {
                counts.merge(r.getTaskId(), 1, Integer::sum);
            }
        }
        Set<Long> viewerThanked;
        if (viewerEmail == null || viewerEmail.isBlank()) {
            viewerThanked = Set.of();
        } else {
            viewerThanked = new HashSet<>(reactionRepo.findTaskIdsWhereViewerReacted(
                    taskIds, viewerEmail.trim().toLowerCase(Locale.ROOT), THANK_EMOJI));
        }
        return new ThankSummary(counts, viewerThanked);
    }

    /** Bundle returned by {@link #loadThankSummary}. */
    public record ThankSummary(Map<Long, Integer> counts, Set<Long> viewerThanked) {
        public int countFor(Long taskId) {
            Integer c = counts.get(taskId);
            return c == null ? 0 : c;
        }
        public boolean viewerThankedTask(Long taskId) {
            return viewerThanked.contains(taskId);
        }
    }

    // ------------------------------------------------------------------

    private Map<String, List<PostReactionDto>> groupByEmoji(List<PostReaction> rows) {
        if (rows == null || rows.isEmpty()) return new LinkedHashMap<>();
        rows.sort(Comparator.comparing(PostReaction::getAddedAt));
        Map<String, List<PostReactionDto>> out = new LinkedHashMap<>();
        for (PostReaction r : rows) {
            out.computeIfAbsent(r.getEmoji(), k -> new ArrayList<>())
                    .add(new PostReactionDto(r.getUserEmail(), r.getAddedAt()));
        }
        return out;
    }

    private Post loadTaskOr404(Long taskId) {
        return taskRepo.findById(taskId)
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
                @Override public void afterCommit() { ws.sendTaskReaction(frame); }
            });
        } else {
            ws.sendTaskReaction(frame);
        }
    }
}
