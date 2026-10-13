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
            String uploadToken,
            /**
             * Who sees this share, as the sharer's own words for it: the
             * group's name ("Plouis household"), "A and B", or "A and 2 other
             * groups". Drawn on the sharer's own Live Activity; never a
             * person's name.
             */
            String audienceLabel
    ) {}

    public record LiveLocationPointRequest(
            Double lat,
            Double lng,
            Double accuracyM,
            Double speedMps,
            Double headingDeg,
            Instant capturedAt,
            /** Optional {@code phone | watch | web} (V83); anything else is stored as null. */
            String source
    ) {
        /** The pre-V83 shape. */
        public LiveLocationPointRequest(Double lat, Double lng, Double accuracyM,
                                        Double speedMps, Double headingDeg, Instant capturedAt) {
            this(lat, lng, accuracyM, speedMps, headingDeg, capturedAt, null);
        }
    }

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
