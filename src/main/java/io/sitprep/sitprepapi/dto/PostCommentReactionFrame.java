package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * STOMP frame broadcast on {@code /topic/post-comments/{postId}} when a
 * viewer adds or removes an emoji reaction on a comment under that post.
 *
 * <p>Rides the same per-post-thread topic that the comment itself is
 * broadcast on (subscribers to a post's thread already listen there);
 * the {@code type:"reaction"} discriminator lets the comment-list
 * subscriber tell reaction frames apart from full {@link PostCommentDto}
 * broadcasts.</p>
 *
 * <p>Mirrors {@link PostReactionFrame} (post-level) modulo
 * {@code postId → postCommentId}; carries the parent {@code postId}
 * for routing since the topic is keyed by postId not commentId.</p>
 */
public record PostCommentReactionFrame(
        String type,        // always "reaction"
        Long postCommentId,
        /** Parent post id — used to route to /topic/post-comments/{postId}. */
        Long postId,
        String emoji,
        String userEmail,
        String action,      // "add" | "remove"
        Instant at
) {
    public static PostCommentReactionFrame add(Long postCommentId, Long postId,
                                               String emoji, String userEmail, Instant at) {
        return new PostCommentReactionFrame(
                "reaction", postCommentId, postId, emoji, userEmail, "add", at);
    }

    public static PostCommentReactionFrame remove(Long postCommentId, Long postId,
                                                  String emoji, String userEmail, Instant at) {
        return new PostCommentReactionFrame(
                "reaction", postCommentId, postId, emoji, userEmail, "remove", at);
    }
}
