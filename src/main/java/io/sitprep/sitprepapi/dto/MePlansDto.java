package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;

/**
 * Lazy-loaded plans payload for the /me hydration. Split out of {@link MeDto}
 * so the main dashboard / nav surfaces don't ship five plan arrays they
 * never render. Pages that actually need plan data fetch
 * {@code GET /api/me/{uid}/plans} on demand (see {@code useMyPlans()} on
 * the frontend).
 *
 * Existence flags for readiness still live on {@code MeDto.readiness} so
 * the dashboard doesn't need this DTO just to draw the readiness ring.
 */
public record MePlansDto(
        MealPlanSummary mealPlan,
        List<EvacPlanSummary> evacuation,
        List<MeetingPlaceSummary> meetingPlaces,
        List<OriginLocationSummary> originLocations,
        List<EmergencyContactGroupSummary> emergencyContactGroups,
        MetaDto meta
) {

    public record MealPlanSummary(
            Long id,
            Integer planDurationQuantity,
            String planDurationUnit,
            int numberOfMenuOptions,
            int planCount
    ) {}

    public record EvacPlanSummary(
            Long id,
            String name,
            String origin,
            String destination,
            boolean deploy,
            String shelterName,
            Double lat,
            Double lng,
            String travelMode
    ) {}

    public record MeetingPlaceSummary(
            Long id,
            String name,
            String location,
            String address,
            String phoneNumber,
            Double lat,
            Double lng,
            boolean deploy
    ) {}

    public record OriginLocationSummary(
            Long id,
            String name,
            String address,
            Double lat,
            Double lng
    ) {}

    public record EmergencyContactGroupSummary(
            Long id,
            String name,
            int contactCount
    ) {}

    public record MetaDto(
            Instant generatedAt,
            int version
    ) {}
}
