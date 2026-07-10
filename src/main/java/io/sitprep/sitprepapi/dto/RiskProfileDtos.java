package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;

/**
 * Location-Based Risk Engine (Phase 1 MVP) response shapes. The backend
 * resolves the household's home location, cross-references a STATIC rule
 * catalog (state / zip-prefix → recurring hazards), and appends
 * hazard-specific requirements on top of the national baseline. The frontend
 * is dumb: it renders these strings verbatim and owns none of the geo logic.
 *
 * <p><b>MVP disclaimer:</b> {@code datasetVersion} marks this as a heuristic
 * state-level mapping, NOT certification-grade. FEMA NRI/RAPT-derived data is
 * the Phase 5 authoritative source (see docs FEMA_RED_CROSS_GAP_ANALYSIS.md).</p>
 */
public final class RiskProfileDtos {

    private RiskProfileDtos() {}

    public record RiskProfileDto(
            /** saved_home | household_zip | last_known_zip | unknown */
            String locationBasis,
            /** Resolved coarse geo key (2-letter state code or zip prefix), or null. */
            String geoKey,
            /** Human label for the region ("California", or "your area" when unknown). */
            String regionLabel,
            /** Identified recurring hazards for this location (empty when unknown/unmapped). */
            List<RiskDto> risks,
            /** Baseline + risk-added + active-alert-upgraded checklist items, priority-sorted (top = 0). */
            List<RiskAdjustedRequirementDto> riskAdjustedRequirements,
            /**
             * LIVE hazard alerts (NWS/USGS) currently IN EFFECT near the home
             * location — distinct from the recurring {@code risks} above ("you live
             * in a hurricane zone" is a risk; "Tornado Warning until 5:00 PM" is an
             * active alert). Severe/Extreme + location-verified only; empty when none
             * or the location is unknown. Each also drives a priority-0
             * {@code active_alert_upgraded} requirement in the list above.
             */
            List<ActiveAlertDto> activeAlerts,
            Instant generatedAt,
            /** Rule-catalog version marker (heuristic MVP). */
            String datasetVersion
    ) {}

    public record RiskDto(
            /** wildfire | earthquake | hurricane | flood | blizzard | extreme_heat | tornado */
            String hazard,
            String label,
            /** very_high | high | moderate | low */
            String tier,
            String reason,
            String source
    ) {}

    public record RiskAdjustedRequirementDto(
            String key,
            /** Owning hazard, or null for the location-prompt CTA. */
            String hazard,
            String label,
            String detail,
            /** 0 = top priority (the location prompt when unknown). */
            int priority,
            String cta,
            String route,
            /** baseline | risk_added | location_prompt | active_alert_upgraded */
            String origin
    ) {}

    /**
     * A LIVE hazard alert in effect near the home location (mirrors the ingest
     * {@code NormalizedAlert} the /api/alerts feed already ships). The frontend is
     * dumb: it renders the backend-authored headline, severity, area, and
     * life-safety {@code instruction} verbatim.
     */
    public record ActiveAlertDto(
            String id,
            /** NWS | USGS | FEMA */
            String source,
            /** Extreme | Severe | Moderate | Minor (raw upstream string — no enum). */
            String severity,
            /** Inferred: tornado | hurricane | flood | wildfire | blizzard | extreme_heat | earthquake | other */
            String hazard,
            /** Backend-authored headline (verbatim from the alert). */
            String headline,
            /** Affected-area text, e.g. "Tarrant County, TX". */
            String area,
            /** Life-safety action to take NOW (backend-authored). */
            String instruction,
            /** ISO-8601 effective time (nullable). */
            String startedAt,
            /** ISO-8601 "in effect until" (nullable). */
            String endsAt
    ) {}
}
