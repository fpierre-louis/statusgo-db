package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Shapes for the Global Readiness Engine (Thick-Server refactor, Phase 1.3 +
 * assessment migration). Three families:
 *
 * <ul>
 *   <li><b>Pulse</b> — the 4-pillar household readiness pulse. The BE computes
 *       displayed percents (the {@code completed / max(added, recommendedMin)}
 *       formula that previously lived in {@code useReadinessPulse.js}),
 *       overall, and tier; the FE renders verbatim.</li>
 *   <li><b>Comms</b> — Communications/Contacts pillar evaluation: actionable
 *       gaps derived from the Phase 3 hardened contracts
 *       ({@link EmergencyContactGroupDto}, {@link MeetingPlaceDto}).</li>
 *   <li><b>Assessment</b> — the weighted self-report scoring engine ported
 *       from the FE {@code assessmentModel.js} (deleted): question catalog +
 *       evaluate request/summary.</li>
 * </ul>
 */
public final class ReadinessDtos {

    private ReadinessDtos() {}

    // -----------------------------
    // Pulse (4-pillar household readiness)
    // -----------------------------

    public record PulseDto(
            List<PulsePillarDto> pillars,
            /** round(mean of pillar pcts). */
            int overall,
            /** set | good | coming | gaps | start (thresholds 90/70/50/30). */
            String tierKey,
            String tierLabel
    ) {}

    public record PulsePillarDto(
            /** supplies | plan | practice | family */
            String key,
            int pct,
            /** Final display string ("3 of 5 done", "Active right now"), or null. */
            String hint
    ) {}

    public record PillarScoreDto(
            /** supplies | evacuation_plan | practice | family | communications */
            String key,
            String label,
            int score,
            /** set | good | coming | gaps | start */
            String status,
            String hint
    ) {}

    // -----------------------------
    // Communications / Contacts pillar
    // -----------------------------

    public record CommsReadinessDto(
            int score,
            int contactsScore,
            int meetingPlacesScore,
            boolean hasContacts,
            int contactCount,
            boolean hasOutOfAreaContact,
            boolean hasLocalContact,
            boolean hasMedicalContact,
            /** True when at least one contact lacks a usable phone number. */
            boolean contactsMissingPhoneNumbers,
            /** True when no contact has an email or address fallback channel. */
            boolean contactsMissingAltMethod,
            boolean missingLocalContact,
            boolean missingOutOfAreaContact,
            boolean missingIndoorSafeRoom,
            boolean missingOutsideHomeMeetup,
            boolean missingOutOfTownMeetup,
            boolean missingNearHomeMeetup,
            boolean missingNeighborhoodMeetup,
            boolean missingInTownMeetup,
            boolean missingOutOfAreaMeetup,
            /** Server-authored, ordered, actionable. FE renders as-is. */
            List<CommsRecommendationDto> recommendations
    ) {}

    public record CommsRecommendationDto(
            String key,
            String label,
            String cta,
            String route
    ) {}

    // -----------------------------
    // Assessment (self-report quiz)
    // -----------------------------

    public record AssessmentQuestionDto(
            String field,
            String title,
            String eyebrow,
            String question,
            /** "single" | "multi-select" */
            String type,
            /** True → only shown after "go deeper" (was {@code more} on the FE). */
            boolean deep,
            /** Non-null → only shown when this hazard is selected (declarative
             *  replacement for the FE's condition closures). */
            String conditionHazard,
            List<AssessmentOptionDto> options
    ) {}

    /** Option value is heterogeneous: selfGrade uses numbers, the rest strings. */
    public record AssessmentOptionDto(String label, String subLabel, Object value) {}

    public record AssessmentEvaluateRequest(
            Map<String, Object> responses,
            List<String> activeHazards
    ) {}

    public record AssessmentSummaryDto(
            /** 2 = server-computed (1 was the FE-computed shape). */
            int version,
            Instant completedAt,
            /** authenticated | guest */
            String mode,
            int score,
            /** strong_foundation | few_gaps | start_with_essentials */
            String band,
            String bandTitle,
            String bandBody,
            int selfGrade,
            List<String> selectedHazards,
            PlanSignalsDto planSignals,
            List<AssessmentItemDto> gaps,
            List<AssessmentItemDto> strengths,
            NextActionDto nextAction,
            List<GuideActionDto> guideActions
    ) {}

    public record PlanSignalsDto(
            boolean hasHousehold,
            boolean hasDemographics,
            boolean hasMealPlan,
            boolean hasEvacuationPlan,
            boolean hasMeetingPlaces,
            boolean hasContacts,
            boolean hasOrigins,
            boolean hasPets
    ) {}

    public record AssessmentItemDto(
            String key,
            String label,
            String route,
            String cta,
            /** critical | high | medium */
            String severity,
            Integer priority,
            String hazardKey,
            String guideSlug,
            /** Pillar link for PillarContextBanner (was FIELD_REGISTRY lookup). */
            String pillarKey,
            /** both | saved_plan_started | self_report */
            String source,
            String reason
    ) {}

    public record NextActionDto(
            /** app_step | guide | review */
            String type,
            String key,
            String title,
            String description,
            String route,
            String cta,
            boolean requiresAuth
    ) {}

    public record GuideActionDto(
            String hazardKey,
            String title,
            String route,
            String accent,
            String accentBg,
            String reason
    ) {}
}
