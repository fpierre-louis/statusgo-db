package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;

/**
 * ADVANCED evacuation readiness — a standalone advisory surface (mirrors the
 * 14-Day Home Stockpile). Reports whether the household has gone beyond a single
 * evacuation route: an ALTERNATE route for blocked roads, and OFFLINE MAPS for
 * cell outages.
 *
 * <p><b>{@code tier == "ADVANCED"}.</b> Like the stockpile, this is exposed on
 * its OWN endpoint and is deliberately NOT wired into
 * {@code HouseholdReadinessService}'s pillar scoring — so these metrics can never
 * lower a household's baseline {@code readinessPercent}. The one baseline signal
 * (a set primary route) lives in the readiness pillar; {@code hasPrimaryRoute} is
 * carried here only for display context, not scored on this surface.</p>
 */
public record EvacuationAdvancedDto(
        /** "evacuation_advanced" */
        String context,
        /** "ADVANCED" */
        String tier,
        String title,
        String summary,
        /** Completion over the advanced metrics only (0 / 50 / 100). */
        int percentComplete,
        /** Baseline context for the FE — whether a primary route is set (not scored here). */
        boolean hasPrimaryRoute,
        List<EvacMetricDto> metrics,
        Instant generatedAt
) {
    public record EvacMetricDto(
            /** "alternate_route" | "offline_maps" */
            String key,
            String label,
            String detail,
            boolean satisfied,
            /** Non-null only when unmet — the CTA label. */
            String cta,
            /** Non-null only when unmet — "/evacuation-wizard". */
            String route
    ) {}
}
