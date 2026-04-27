package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;

/**
 * Core /me payload — profile, household, groups, readiness, meta.
 *
 * Plans (mealPlan, evacuation, meetingPlaces, originLocations, contacts)
 * were dropped from this DTO and live behind {@code GET /api/me/{uid}/plans}
 * (see {@link MePlansDto}). The dashboard / nav / status surfaces don't
 * need them; only {@code me/plans/*} pages do.
 *
 * Readiness existence flags (which steps are done) still live here so the
 * dashboard ring renders from one round trip.
 */
public record MeDto(
        ProfileDto profile,
        HouseholdDto household,
        GroupsDto groups,
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
