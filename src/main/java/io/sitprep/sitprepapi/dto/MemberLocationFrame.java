package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * Live member location frame.
 *
 * <p>Topic: {@code /topic/group/{groupId}/members/location}</p>
 *
 * <p>Payload mirrors the privacy-gated location fields of
 * {@code GroupMemberViewDto.MemberSummary}. The service only publishes this
 * frame to groups where the member's sharing mode currently allows location,
 * so every field here — including the V83 ones — rides the same gate.</p>
 *
 * <p>The last four components (V83, map-ideal BE-2) are appended; a client
 * that predates them ignores them. They carry the same meaning and names as
 * on {@code MemberSummary}.</p>
 */
public record MemberLocationFrame(
        String email,
        Double latitude,
        Double longitude,
        Instant updatedAt,
        GroupMemberViewDto.AtPlace atPlace,
        String lastSeenNear,
        String locationSource,
        Integer locationAccuracyM
) {
    /** The pre-V83 shape. */
    public MemberLocationFrame(String email, Double latitude, Double longitude, Instant updatedAt) {
        this(email, latitude, longitude, updatedAt, null, null, null, null);
    }
}
