package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * Group alert state frame.
 *
 * <p>Topic: {@code /topic/group/{groupId}/status}</p>
 */
public record GroupAlertFrame(
        String groupId,
        String alert,                // Active | Cleared
        Instant alertActivatedAt,
        String initiatedByEmail,
        String reason,               // manual | decay | continue (window moved, state unchanged)
        Instant alertExpiresAt       // when an Active check-in ends by itself; null when Cleared
) {}
