package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * STOMP frame broadcast on {@code /topic/group-post-comments/{postId}}
 * when a viewer adds or removes an emoji reaction on a chat-comment
 * under that post. Mirrors {@link PostCommentReactionFrame} (community
 * side) modulo the routing prefix.
 *
 * <p>Rides the same per-thread topic that the comment itself was
 * broadcast on; the {@code type:"reaction"} discriminator lets the
 * comment-list subscriber tell reaction frames apart from full
 * {@link GroupPostCommentDto} broadcasts.</p>
 */
public record GroupPostCommentReactionFrame(
        String type,        // always "reaction"
        Long groupPostCommentId,
        /** Parent post id — used to route to /topic/group-post-comments/{postId}. */
        Long postId,
        String emoji,
        String userEmail,
        String action,      // "add" | "remove"
        Instant at
) {
    public static GroupPostCommentReactionFrame add(Long groupPostCommentId, Long postId,
                                                    String emoji, String userEmail, Instant at) {
        return new GroupPostCommentReactionFrame(
                "reaction", groupPostCommentId, postId, emoji, userEmail, "add", at);
    }

    public static GroupPostCommentReactionFrame remove(Long groupPostCommentId, Long postId,
                                                       String emoji, String userEmail, Instant at) {
        return new GroupPostCommentReactionFrame(
                "reaction", groupPostCommentId, postId, emoji, userEmail, "remove", at);
    }
}
