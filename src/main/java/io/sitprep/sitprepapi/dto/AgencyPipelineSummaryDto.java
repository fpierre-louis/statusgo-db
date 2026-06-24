package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Aggregate health of the agency verification pipeline — drives the
 * super-admin readiness card on {@code /admin/agency-onboarding}
 * (Phase 5 Slice G). Counts by status plus a few named rollups and the
 * median submit&rarr;provision time, computed server-side so the FE stays
 * a thin view layer.
 *
 * @param total                 every application on record
 * @param counts                status name &rarr; count (all enum states, zeros included)
 * @param awaitingReview        SUBMITTED + IN_REVIEW + NEEDS_INFO — the reviewer's queue
 * @param approved             stamp granted, not yet provisioned
 * @param provisioned          live agencies (workspace handed off)
 * @param medianHoursToProvision  median submit&rarr;provision time, or null if none provisioned
 * @param medianHoursToClaim   median submit&rarr;claim/assign time, or null if none claimed
 * @param stuckOpen            open requests untouched for several days
 * @param stuckRequests        compact list of stuck requests for the overview
 */
public record AgencyPipelineSummaryDto(
        long total,
        Map<String, Long> counts,
        long awaitingReview,
        long approved,
        long provisioned,
        Double medianHoursToProvision,
        Double medianHoursToClaim,
        long stuckOpen,
        List<StuckRequest> stuckRequests
) {
    public record StuckRequest(
            Long id,
            String agencyName,
            String status,
            String assignedConsultantEmail,
            Instant submittedAt,
            Instant updatedAt,
            long ageDays
    ) {}
}
