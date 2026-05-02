package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * STOMP frame broadcast on {@code /topic/posts/{groupId}} when a viewer adds
 * or removes an emoji reaction. The {@code type} discriminator lets clients
 * tell reaction frames apart from full {@link GroupPostDto} broadcasts on the same
 * topic.
 */
public record GroupPostReactionFrame(
        String type,        // always "reaction"
        Long postId,
        String groupId,
        String emoji,
        String userEmail,
        String action,      // "add" | "remove"
        Instant at
) {
    public static GroupPostReactionFrame add(Long postId, String groupId, String emoji, String userEmail, Instant at) {
        return new GroupPostReactionFrame("reaction", postId, groupId, emoji, userEmail, "add", at);
    }

    public static GroupPostReactionFrame remove(Long postId, String groupId, String emoji, String userEmail, Instant at) {
        return new GroupPostReactionFrame("reaction", postId, groupId, emoji, userEmail, "remove", at);
    }
}
