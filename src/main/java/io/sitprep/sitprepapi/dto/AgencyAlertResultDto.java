package io.sitprep.sitprepapi.dto;

import java.util.List;

/**
 * Result of an agency geo-alert send (Phase 5 Slice D).
 *
 * @param duplicate true when this send collided with the idempotency guard —
 *                  nothing was re-dispatched; the prior result is returned.
 */
public record AgencyAlertResultDto(
        Long id,
        Long postId,
        int recipientCount,
        List<String> targetedZips,
        boolean duplicate
) {}
