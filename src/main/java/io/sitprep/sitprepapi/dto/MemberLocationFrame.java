package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * Live member location frame.
 *
 * <p>Topic: {@code /topic/group/{groupId}/members/location}</p>
 *
 * <p>Payload mirrors the privacy-gated {@code lastKnownLat/Lng} fields in
 * {@code GroupMemberViewDto.MemberSummary}. The service only publishes this
 * frame to groups where the member's sharing mode currently allows location.</p>
 */
public record MemberLocationFrame(
        String email,
        Double latitude,
        Double longitude,
        Instant updatedAt
) {}
