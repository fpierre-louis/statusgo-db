package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * Live household member presence frame.
 *
 * <p>Topic: {@code /topic/households/{householdId}/presence}</p>
 */
public record MemberPresenceFrame(
        String email,
        boolean online,
        int onlineCount,
        Instant updatedAt
) {}
