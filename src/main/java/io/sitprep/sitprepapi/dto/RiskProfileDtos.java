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
            /** Baseline + risk-added checklist items, priority-sorted (top = 0). */
            List<RiskAdjustedRequirementDto> riskAdjustedRequirements,
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
            /** baseline | risk_added | location_prompt */
            String origin
    ) {}
}
