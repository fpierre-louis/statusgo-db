package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.EvacuationPlan;
import io.sitprep.sitprepapi.dto.GoBagDtos.GoBagSummaryDto;

import java.time.Instant;
import java.util.List;

/**
 * Read shape for {@link EvacuationPlan} (Thin-Client Refactor Phase 3 — DTO
 * hardening). Strips {@code ownerEmail} + {@code householdId} (internal
 * owner/household routing — the caller is the verified owner); keeps the
 * surrogate {@code id} as the FE's row handle. Field names mirror the entity
 * so the wire contract stays stable.
 *
 * <p>{@code goBags} (2026-07-09): the household's go-bag summaries ride on
 * each plan row — the go bag is an extension of the evacuation plan ("grab it
 * on the way out"). The resource assembles the list ONCE per request (single
 * batched query via {@code GoBagService.summariesForHousehold}) and shares it
 * across rows; with ≤ a handful of plans and bags the duplication is bytes,
 * and the wire shape stays a plain array.</p>
 */
public record EvacuationPlanDto(
        Long id,
        String name,
        String origin,
        String destination,
        boolean deploy,
        String shelterName,
        String shelterAddress,
        String shelterPhoneNumber,
        Double lat,
        Double lng,
        String travelMode,
        String shelterInfo,
        // Evacuation route semantics (V35). primaryRouteNotes = BASELINE;
        // alternateRouteNotes + offlineMapSaved = ADVANCED (never affect baseline).
        String primaryRouteNotes,
        String alternateRouteNotes,
        boolean offlineMapSaved,
        Instant lastPracticedAt,
        List<GoBagSummaryDto> goBags
) {
    public static EvacuationPlanDto from(EvacuationPlan e, List<GoBagSummaryDto> goBags) {
        return new EvacuationPlanDto(
                e.getId(),
                e.getName(),
                e.getOrigin(),
                e.getDestination(),
                e.isDeploy(),
                e.getShelterName(),
                e.getShelterAddress(),
                e.getShelterPhoneNumber(),
                e.getLat(),
                e.getLng(),
                e.getTravelMode(),
                e.getShelterInfo(),
                e.getPrimaryRouteNotes(),
                e.getAlternateRouteNotes(),
                e.isOfflineMapSaved(),
                e.getLastPracticedAt(),
                goBags == null ? List.of() : goBags);
    }
}
