package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;

public final class LiveLocationDtos {
    private LiveLocationDtos() {}

    public record StartLiveLocationSessionRequest(
            List<String> groupIds,
            Integer durationMinutes,
            String activationId,
            String alertId
    ) {}

    public record LiveLocationSessionDto(
            String id,
            String userEmail,
            List<String> groupIds,
            String activationId,
            String alertId,
            Instant startedAt,
            Instant expiresAt,
            Instant stoppedAt,
            String uploadToken
    ) {}

    public record LiveLocationPointRequest(
            Double lat,
            Double lng,
            Double accuracyM,
            Double speedMps,
            Double headingDeg,
            Instant capturedAt
    ) {}

    public record LiveLocationMemberDto(
            String sessionId,
            String email,
            Double latitude,
            Double longitude,
            Double accuracyM,
            Double speedMps,
            Double headingDeg,
            Instant updatedAt,
            Instant expiresAt
    ) {}

    public record LiveLocationFrame(
            String type,
            String sessionId,
            String email,
            Double latitude,
            Double longitude,
            Double accuracyM,
            Double speedMps,
            Double headingDeg,
            Instant updatedAt,
            Instant expiresAt,
            boolean active,
            boolean stopped
    ) {}
}
