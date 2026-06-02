package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * Request body for PATCH /api/userinfo/me/status.
 *
 * <p>Status must be SAFE, HELP, or INJURED. The client may provide its
 * optimistic timestamp; the service stamps now when it is omitted.</p>
 */
public record UpdateSelfStatusRequest(
        String status,
        String color,
        Instant updatedAt
) {}
