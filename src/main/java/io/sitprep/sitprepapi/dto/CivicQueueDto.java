package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;

/**
 * Civic epic Slice 1 — an agency's civic-report pending queue (READ-ONLY).
 * Returned by {@code GET /api/agencies/{groupId}/civic-reports}.
 *
 * <p><b>Designed to evolve (decision 8, multi-agency).</b> Each report's
 * {@link CivicReportSummary#taggedAgencies()} is a <b>List</b> from day one —
 * today it always holds exactly the one tagged agency, but Slice 2's
 * multi-agency model changes only how that list is populated, not this
 * contract. Likewise each row exposes {@code latitude}/{@code longitude} so the
 * deferred map layer reuses the same DTO with no reshape.</p>
 *
 * @param counts  per-status counts across ALL of the agency's civic reports
 *                (drives the FE status tab bar, independent of the applied
 *                {@code status} filter)
 * @param reports the reports themselves, newest first — filtered to the
 *                requested status when one was passed, else all
 */
public record CivicQueueDto(
        CivicQueueCounts counts,
        List<CivicReportSummary> reports
) {

    /**
     * Per-status counts of an agency's civic reports. Mirrors the
     * {@code CivicStatus} lifecycle; {@code total} is the sum.
     */
    public record CivicQueueCounts(
            int reported, int acknowledged, int scheduled, int resolved, int total
    ) {}

    /**
     * One civic report as the queue sees it — category + status + timestamps +
     * location for display (city/state via the existing reverse-geocode data,
     * never recomputed) and map-ready coordinates. Read-only in Slice 1.
     *
     * @param id             the civic-report post id
     * @param category       {@code CivicCategory} wire (pothole/streetlight/…)
     * @param status         {@code CivicStatus} wire (reported/acknowledged/…)
     * @param title          nullable — civic reports are body-first
     * @param description    the resident's report body
     * @param latitude       map-ready; null on legacy rows with no fix
     * @param longitude      map-ready; null on legacy rows with no fix
     * @param placeLabel     displayable location (neighborhood → city → state)
     *                       from the reverse-geocode at create; the FE renders
     *                       this until a street-level address is captured
     * @param requesterEmail the filer (for the future +1/me-too counting)
     * @param createdAt      filed at
     * @param acknowledgedAt when an agency acknowledged, else null
     * @param scheduledFor   when scheduled, else null
     * @param resolvedAt     when resolved, else null
     * @param taggedAgencies the agencies this report is tagged to — a List
     *                       (size 1 today; multi-agency in Slice 2)
     */
    public record CivicReportSummary(
            Long id,
            String category,
            String status,
            String title,
            String description,
            Double latitude,
            Double longitude,
            String placeLabel,
            String requesterEmail,
            Instant createdAt,
            Instant acknowledgedAt,
            Instant scheduledFor,
            Instant resolvedAt,
            List<AgencyRef> taggedAgencies
    ) {}

    /** A tagged agency reference — group id + display name. */
    public record AgencyRef(String groupId, String name) {}
}
