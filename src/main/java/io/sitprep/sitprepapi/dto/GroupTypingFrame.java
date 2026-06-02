package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * Ephemeral chat typing frame.
 *
 * <p>Topic: {@code /topic/group-posts/{groupId}/typing}</p>
 */
public record GroupTypingFrame(
        String type,
        String groupId,
        String email,
        String displayName,
        boolean typing,
        Instant expiresAt,
        Instant updatedAt
) {}
