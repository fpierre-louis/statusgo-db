package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * Live roster delta for group membership changes.
 *
 * <p>Topic: {@code /topic/group/{groupId}/members}</p>
 */
public record GroupMembershipFrame(
        String action,
        String userEmail,
        String role,
        Instant updatedAt
) {}
