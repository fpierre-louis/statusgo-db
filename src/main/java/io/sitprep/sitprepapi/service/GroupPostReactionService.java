package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.GroupPost;
import io.sitprep.sitprepapi.domain.GroupPostReaction;
import io.sitprep.sitprepapi.dto.EmojiReactionDto;
import io.sitprep.sitprepapi.dto.GroupPostReactionFrame;
import io.sitprep.sitprepapi.repo.GroupPostReactionRepo;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
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
 * Add / remove / load emoji reactions on chat posts. Persists to the
 * {@code post_reaction} table; broadcasts a reaction frame on
 * {@code /topic/posts/{groupId}} after commit so other viewers update live.
 */
@Service
public class GroupPostReactionService {

    /**
     * Hard cap on emoji length so we don't accept arbitrary user-provided
     * strings — emoji glyphs are short, but a bad client could send anything.
     * Matches the column length on GroupPostReaction.emoji.
     */
    private static final int MAX_EMOJI_LENGTH = 32;

    private final GroupPostReactionRepo reactionRepo;
    private final GroupPostRepo postRepo;
    private final WebSocketMessageSender ws;

    public GroupPostReactionService(GroupPostReactionRepo reactionRepo,
                               GroupPostRepo postRepo,
                               WebSocketMessageSender ws) {
        this.reactionRepo = reactionRepo;
        this.postRepo = postRepo;
        this.ws = ws;
    }

    @Transactional
    public Map<String, List<EmojiReactionDto>> add(Long postId, String userEmail, String emoji) {
        String normalizedEmoji = sanitizeEmoji(emoji);
        String normalizedEmail = normalizeEmail(userEmail);
        GroupPost post = loadPostOr404(postId);

        Optional<GroupPostReaction> existing = reactionRepo
                .findByPostIdAndUserEmailIgnoreCaseAndEmoji(postId, normalizedEmail, normalizedEmoji);

        Instant at;
        if (existing.isPresent()) {
            // Idempotent: re-adding the same reaction doesn't duplicate or
            // bump addedAt — just returns the current roster.
            at = existing.get().getAddedAt();
        } else {
            GroupPostReaction r = new GroupPostReaction();
            r.setPostId(postId);
            r.setUserEmail(normalizedEmail);
            r.setEmoji(normalizedEmoji);
            reactionRepo.save(r);
            at = r.getAddedAt();
            broadcastAfterCommit(GroupPostReactionFrame.add(
                    postId, post.getGroupId(), normalizedEmoji, normalizedEmail, at));
        }

        return loadByPostId(postId);
    }

    @Transactional
    public Map<String, List<EmojiReactionDto>> remove(Long postId, String userEmail, String emoji) {
        String normalizedEmoji = sanitizeEmoji(emoji);
        String normalizedEmail = normalizeEmail(userEmail);
        GroupPost post = loadPostOr404(postId);

        int deleted = reactionRepo.deleteByPostUserEmoji(postId, normalizedEmail, normalizedEmoji);
        if (deleted > 0) {
            broadcastAfterCommit(GroupPostReactionFrame.remove(
                    postId, post.getGroupId(), normalizedEmoji, normalizedEmail, Instant.now()));
        }
        return loadByPostId(postId);
    }

    /** Single-post load used by the resource GET and by the per-post DTO build. */
    public Map<String, List<EmojiReactionDto>> loadByPostId(Long postId) {
        return groupByEmoji(reactionRepo.findByPostId(postId));
    }

    /**
     * Batched load for a post listing — one repo round trip for all posts
     * in the page. Returns a map keyed by post id; absent post ids have no
     * reactions and are simply not in the map.
     */
    public Map<Long, Map<String, List<EmojiReactionDto>>> loadByPostIds(Collection<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) return Collections.emptyMap();
        List<GroupPostReaction> rows = reactionRepo.findByPostIdIn(postIds);
        if (rows.isEmpty()) return Collections.emptyMap();

        Map<Long, List<GroupPostReaction>> byPost = rows.stream()
                .collect(Collectors.groupingBy(GroupPostReaction::getPostId));
        Map<Long, Map<String, List<EmojiReactionDto>>> out = new HashMap<>(byPost.size());
        byPost.forEach((postId, list) -> out.put(postId, groupByEmoji(list)));
        return out;
    }

    // ------------------------------------------------------------------

    private Map<String, List<EmojiReactionDto>> groupByEmoji(List<GroupPostReaction> rows) {
        if (rows == null || rows.isEmpty()) return new LinkedHashMap<>();
        // Sort by addedAt so the roster is deterministic across calls.
        rows.sort(Comparator.comparing(GroupPostReaction::getAddedAt));
        Map<String, List<EmojiReactionDto>> out = new LinkedHashMap<>();
        for (GroupPostReaction r : rows) {
            out.computeIfAbsent(r.getEmoji(), k -> new ArrayList<>())
                    .add(new EmojiReactionDto(r.getUserEmail(), r.getAddedAt()));
        }
        return out;
    }

    private GroupPost loadPostOr404(Long postId) {
        return postRepo.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "GroupPost not found"));
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

    private void broadcastAfterCommit(GroupPostReactionFrame frame) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { ws.sendGroupPostReaction(frame.groupId(), frame); }
            });
        } else {
            ws.sendGroupPostReaction(frame.groupId(), frame);
        }
    }
}
