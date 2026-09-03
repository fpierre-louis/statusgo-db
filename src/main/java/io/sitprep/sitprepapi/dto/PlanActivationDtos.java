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
            String operationalMode,
            String movementDirective,
            GoverningAlertDto governingAlert,
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
            /**
             * True once the activation is over EITHER WAY — past {@code expiresAt},
             * or ended by the household. The two are not the same event and a
             * surface that needs to tell them apart reads {@code endedAt}; one
             * that only needs "is this still running" reads this.
             */
            boolean closed,
            /**
             * When a person ended it, or null. Null with {@code closed == true}
             * means the 72-hour timer ran out — nobody said it was over, it just
             * stopped being live.
             */
            Instant endedAt,
            /**
             * May THIS caller end the activation? Server-computed, per the
             * role-resolution convention (CLAUDE.md): a lightweight capability
             * the client renders, never a membership the client re-derives.
             * The frontend has no reliable way to tell a household co-member
             * from a link holder out of this payload, and guessing from the
             * shape of it ("acks came back, so I must be family") is the kind
             * of inference that breaks the first time the projection changes.
             * Always false in the recipient projection.
             */
            boolean viewerCanEnd,
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
            List<AckDto> acks,
            /**
             * Server-computed ack rollup (Thin-Client Refactor Phase 1) — the
             * authoritative snapshot of "who has responded", for consumers that
             * want the summary without subscribing to the live STOMP ack stream
             * (owner home badge, notifications, future surfaces). The live
             * Responses board still reduces the streamed list client-side.
             * Owner/household audience only; null in the recipient projection.
             */
            AckRollupDto ackRollup,
            /**
             * Canonical Active Situation contract for every FE surface. Raw
             * meeting/evac fields remain for detail rendering; this is the
             * resolved instruction and mode.
             */
            ActiveSituationDto activeSituation
    ) {}

    public record ActiveSituationDto(
            String id,
            /** "active" while running; "closed" once expired OR ended. */
            String status,
            Instant activatedAt,
            Instant updatedAt,
            /** When a person ended it; null if it is running or merely expired. */
            Instant endedAt,
            String requestedOperationalMode,
            String operationalMode,
            String movementDirective,
            MeetingPlaceSnapshotDto activeMeetingPlace,
            EvacuationPlanSnapshotDto activeEvacuationDestination,
            GoverningAlertDto governingAlert,
            AckRollupDto checkInSummary,
            String primaryAction,
            String primaryActionKind,
            String suppressedAction,
            String suppressedReason
    ) {}

    public record GoverningAlertDto(
            String source,
            String id,
            String event,
            String headline,
            String lifecycleState
    ) {}

    public record AckRollupDto(
            /** Total acks received. There is no intended-recipient denominator. */
            int replies,
            int safe,
            int help,
            int pickup,
            int other
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

    /**
     * The activation's LIFECYCLE, as opposed to its plan data.
     *
     * <p>Shares the {@code /topic/activations/{id}/plan} destination with
     * {@link ActivationPlanUpdatedFrame} — already authorised, already carried,
     * and the right audience by construction: a recipient watching a shared
     * link has an activation id and no household id, so a household-scoped
     * topic could not reach the people who most need to hear that it ended.
     * The {@code type} field is what a client discriminates on, exactly as the
     * plan frame already does.</p>
     *
     * <p>{@code state} is {@code "started"} or {@code "ended"}. {@code byEmail}
     * is null when the 72-hour timer ended it rather than a person.</p>
     */
    public record ActivationLifecycleFrame(
            String type,
            String activationId,
            String state,
            String byEmail,
            Instant at
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
