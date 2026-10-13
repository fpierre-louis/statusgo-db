package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;

/**
 * Location-Based Risk Engine (Phase 1 MVP) response shapes. The backend
 * resolves the household's home location, cross-references a STATIC rule
 * catalog (state / zip-prefix → recurring hazards), and appends
 * hazard-specific requirements on top of the national baseline. The frontend
 * is dumb: it renders these strings verbatim and owns none of the geo logic.
 * Live-alert text follows the alert safety policy — see {@link ActiveAlertDto}.
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
             * active alert). Severe/Extreme + location-verified + still in force
             * only; empty when none or the location is unknown. A WARNING-tier
             * alert also drives a priority-0 {@code active_alert_upgraded}
             * requirement in the list above; watches and advisories do not.
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
     * {@code NormalizedAlert} the /api/alerts feed already ships).
     *
     * <p><b>Two kinds of text, never one field.</b> {@code instruction} is the
     * issuer's own CAP instruction, verbatim, and SitPrep never writes into it.
     * {@code whatToDo} is SitPrep's reviewed plain-language copy and is present
     * only when {@link io.sitprep.sitprepapi.service.AlertSafetyPolicy} allows
     * SitPrep guidance for this alert. This used to be a single
     * {@code instruction} holding a hand-written per-hazard directive ("Take
     * cover now", "Follow evacuation orders now") attached to every
     * Severe/Extreme alert, watches included, with no policy gate. Owner ruling
     * R2 (2026-10-13) removed it: under the field name CAP uses for the issuer's
     * words, it read as the issuer's words.</p>
     *
     * <p>The frontend renders {@code whatToDo} when present, else
     * {@code instruction} labelled with {@code issuer}, else nothing.</p>
     */
    public record ActiveAlertDto(
            String id,
            /** NWS | USGS | FEMA */
            String source,
            /** Extreme | Severe | Moderate | Minor (raw upstream string — no enum). */
            String severity,
            /**
             * Inferred for the icon and the requirement key only: tornado | hurricane
             * | flood | wildfire | blizzard | extreme_heat | earthquake | other. Never
             * drives safety text.
             */
            String hazard,
            /** Backend-authored headline (verbatim from the alert). */
            String headline,
            /** Affected-area text, e.g. "Tarrant County, TX". */
            String area,
            /** The issuer's CAP instruction, verbatim; null when the issuer sent none. */
            String instruction,
            /** ISO-8601 effective time (nullable). */
            String startedAt,
            /** ISO-8601 "in effect until" (nullable). */
            String endsAt,
            /** NWS product name verbatim ("Flood Watch"); null for USGS/FEMA. */
            String event,
            /**
             * SitPrep's reviewed guidance for this alert — the same string the
             * /api/alerts/feed card carries in {@code whatToDo}. Null unless the
             * policy decision allows SitPrep guidance (approved, compatible template
             * in {@code supplement_official} mode).
             */
            String whatToDo,
            /**
             * warning | watch | advisory | statement | information — the template's
             * tier when one covers the product, else the product-name suffix (same
             * rule as the feed card). Only {@code warning} produces an
             * {@code active_alert_upgraded} requirement.
             */
            String tier,
            /** Policy guidance mode: supplement_official | official_only | no_guidance. */
            String guidanceMode,
            /**
             * Who issued the alert, for attributing {@code instruction} ("the National
             * Weather Service (NOAA)"); null for an unknown source.
             */
            String issuer
    ) {
        /**
         * Fixture shape for callers that hold no policy decision: no SitPrep
         * guidance, unknown tier. {@code RiskProfileService} always uses the
         * full constructor.
         */
        public ActiveAlertDto(String id, String source, String severity, String hazard,
                              String headline, String area, String instruction,
                              String startedAt, String endsAt, String event) {
            this(id, source, severity, hazard, headline, area, instruction,
                    startedAt, endsAt, event, null, null, null, null);
        }
    }
}
