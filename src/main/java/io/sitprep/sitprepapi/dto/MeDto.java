package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;

public record MeDto(
        ProfileDto profile,
        HouseholdDto household,
        GroupsDto groups,
        PlansDto plans,
        ReadinessDto readiness,
        MetaDto meta
) {

    public record ProfileDto(
            String userId,
            String firebaseUid,
            String email,
            String firstName,
            String lastName,
            String title,
            String phone,
            String address,
            String latitude,
            String longitude,
            String profileImageUrl,
            String subscription,
            SelfStatusDto selfStatus
    ) {}

    public record SelfStatusDto(
            String value,
            String color,
            Instant updatedAt
    ) {}

    public record HouseholdDto(
            String groupId,
            String name,
            String address,
            String latitude,
            String longitude,
            String zipCode,
            int memberCount,
            int adminCount,
            DemographicDto demographic,
            Integer readinessPercent
    ) {}

    public record DemographicDto(
            int adults,
            int kids,
            int infants,
            int dogs,
            int cats,
            int pets
    ) {}

    public record GroupsDto(
            List<GroupSummary> managed,
            List<GroupSummary> joined
    ) {}

    public record GroupSummary(
            String groupId,
            String name,
            String groupType,
            int memberCount,
            int pendingMemberCount,
            String role,
            String alert,
            Instant updatedAt
    ) {}

    public record PlansDto(
            MealPlanSummary mealPlan,
            List<EvacPlanSummary> evacuation,
            List<MeetingPlaceSummary> meetingPlaces,
            List<OriginLocationSummary> originLocations,
            List<EmergencyContactGroupSummary> emergencyContactGroups
    ) {}

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

    public record ReadinessDto(
            int percentComplete,
            List<ReadinessStep> steps
    ) {}

    public record ReadinessStep(
            String key,
            boolean done
    ) {}

    public record MetaDto(
            Instant generatedAt,
            int version
    ) {}
}
