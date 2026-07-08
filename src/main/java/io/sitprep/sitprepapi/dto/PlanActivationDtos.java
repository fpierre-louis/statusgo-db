package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;

/**
 * Request and response shapes for {@code /api/plans/activations}. Grouped in
 * one file because the shapes are small and read together; matches the
 * nested-record style used by {@link MeDto}.
 */
public final class PlanActivationDtos {

    private PlanActivationDtos() {}

    // -----------------------------
    // Requests
    // -----------------------------

    public record CreateActivationRequest(
            /** Required: auth is stripped, so the frontend passes owner identity here. */
            String ownerEmail,
            Long meetingPlaceId,
            Long evacPlanId,
            String meetingMode,
            String evacMode,
            String messagePreview,
            LocationDto location,
            RecipientsRequest recipients
    ) {}

    public record RecipientsRequest(
            List<String> householdMemberIds,
            List<Long> contactIds,
            List<Long> contactGroupIds
    ) {}

    public record AckRequest(
            /** Required: recipient is unauthenticated, so identity rides in the body. */
            String recipientEmail,
            String recipientName,
            String status,
            Double lat,
            Double lng
    ) {}

    // -----------------------------
    // Responses
    // -----------------------------

    public record ActivationCreatedDto(String activationId, Instant expiresAt) {}

    public record ActivationDetailDto(
            String activationId,
            String ownerUserId,
            String ownerName,
            Instant activatedAt,
            Instant expiresAt,
            /** {@code Instant.now().isAfter(expiresAt)} at response time. */
            boolean closed,
            String meetingMode,
            String evacMode,
            String messagePreview,
            LocationDto location,
            MeetingPlaceSnapshotDto meetingPlace,
            EvacuationPlanSnapshotDto evacPlan,
            List<EmergencyContactGroupSnapshotDto> emergencyContactGroups,
            /**
             * "Grab before you go" — household go bags + their storage
             * location + packed/expired rollup. HOUSEHOLD-AUDIENCE ONLY;
             * always {@code List.of()} in the recipient projection (a bag's
             * storage location is not shared with link holders). No product
             * links — this is a crisis surface.
             */
            List<GoBagSnapshotDto> goBags,
            List<AckDto> acks
    ) {}

    public record GoBagSnapshotDto(
            String bagName,
            String storageLabel,
            String kind,
            int itemsPacked,
            int itemsTotal,
            int expiredCount,
            List<String> topUnpackedP0
    ) {}

    public record AckDto(
            Long id,
            String recipientEmail,
            String recipientName,
            String status,
            Double lat,
            Double lng,
            Instant ackedAt
    ) {}

    public record ActivationPlanUpdatedFrame(
            String type,
            String activationId,
            String resourceKind,
            Long version,
            Instant updatedAt
    ) {}

    public record LocationDto(Double lat, Double lng) {}

    public record MeetingPlaceSnapshotDto(
            Long id,
            String name,
            String location,
            String address,
            String phoneNumber,
            String additionalInfo,
            Double lat,
            Double lng
    ) {}

    public record EvacuationPlanSnapshotDto(
            Long id,
            String name,
            String origin,
            String destination,
            String shelterName,
            String shelterAddress,
            String shelterPhoneNumber,
            Double lat,
            Double lng,
            String travelMode,
            String shelterInfo
    ) {}

    public record EmergencyContactGroupSnapshotDto(
            Long id,
            String name,
            List<EmergencyContactSnapshotDto> contacts
    ) {}

    public record EmergencyContactSnapshotDto(
            Long id,
            String name,
            String role,
            String phone,
            String email,
            String address,
            String radioChannel,
            String medicalInfo,
            String subjectType,
            String subjectId,
            String subjectName
    ) {}
}
