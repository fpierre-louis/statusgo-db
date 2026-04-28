package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * STOMP frame broadcast on {@code /topic/posts/{groupId}} when a viewer adds
 * or removes an emoji reaction. The {@code type} discriminator lets clients
 * tell reaction frames apart from full {@link PostDto} broadcasts on the same
 * topic.
 */
public record PostReactionFrame(
        String type,        // always "reaction"
        Long postId,
        String groupId,
        String emoji,
        String userEmail,
        String action,      // "add" | "remove"
        Instant at
) {
    public static PostReactionFrame add(Long postId, String groupId, String emoji, String userEmail, Instant at) {
        return new PostReactionFrame("reaction", postId, groupId, emoji, userEmail, "add", at);
    }

    public static PostReactionFrame remove(Long postId, String groupId, String emoji, String userEmail, Instant at) {
        return new PostReactionFrame("reaction", postId, groupId, emoji, userEmail, "remove", at);
    }
}
