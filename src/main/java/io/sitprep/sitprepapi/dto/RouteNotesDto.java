package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * Payload for the non-destructive route-notes PATCH
 * ({@code PATCH /api/evacuation-plans/routes}). Carries ONLY the evacuation
 * route fields; {@code EvacuationPlanService.updateRouteNotes} merges them onto
 * the household's EXISTING plans in place, so destinations, shelters, and
 * coordinates are never touched (see SYSTEM_TRAPS T-17).
 *
 * <p>{@code lastPracticedAt} is applied only when present (non-null) so a routes
 * save can't clobber a value set by a future drill hook; the wizard never sends it.</p>
 */
public record RouteNotesDto(
        String primaryRouteNotes,
        String alternateRouteNotes,
        boolean offlineMapSaved,
        Instant lastPracticedAt
) {}
