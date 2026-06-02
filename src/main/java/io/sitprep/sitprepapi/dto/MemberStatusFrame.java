package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * Live member status frame.
 *
 * <p>Topics:
 * {@code /topic/households/{householdId}/members/status} and
 * {@code /topic/group/{groupId}/members/status}.</p>
 *
 * <p>Payload mirrors the selfStatus object embedded in MeDto/GroupMemberViewDto
 * so frontend roster surfaces can patch a member chip in place.</p>
 */
public record MemberStatusFrame(
        String email,
        String status,
        String color,
        Instant updatedAt
) {}
