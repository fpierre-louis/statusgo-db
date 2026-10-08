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
 *
 * <p>Sent on EVERY status write, including SAFE while already SAFE — a repeat
 * answer is still the reply an asker's row is waiting for.</p>
 */
public record MemberStatusFrame(
        String email,
        String status,
        String color,
        Instant updatedAt,
        /**
         * First name of the admin who answered FOR this person; null for a
         * self-report. Same meaning as {@code SelfStatus.setByName}, so a live
         * proxy write keeps its attribution. Appended: positional record.
         */
        String setByName
) {}
