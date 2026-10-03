package io.sitprep.sitprepapi.dto;

/**
 * One pin on the household map's "places" layer — a unified, typed feed that
 * replaces the frontend's localStorage assembly of home / meeting places /
 * shelters (gap B of docs/MAP_REBUILD_PLAN.md). Served by
 * {@code GET /api/households/{householdId}/map-places}.
 *
 * @param id       stable, source-prefixed id for FE keying/dedup
 *                 (e.g. {@code "group:<gid>"}, {@code "shelter:<id>"})
 * @param kind     render discriminator: {@code house | meetup | shelter | start}
 * @param lat      latitude (double precision). NULL when the row was saved
 *                 with an address but never geocoded — see {@code mappable}.
 * @param lng      longitude (double precision). NULL under the same condition.
 * @param name     display name
 * @param address  free-form address, may be null
 * @param source   originating table: {@code group | meeting_place |
 *                 evacuation_plan | origin_location}
 * @param mappable whether this place can be drawn on a map — i.e. whether
 *                 {@code lat}/{@code lng} are both present and valid.
 *                 <p>
 *                 This endpoint used to DROP rows without coordinates, which
 *                 made a real saved place invisible: nothing geocodes on write
 *                 (the only caller of forward geocoding is the FE-facing
 *                 {@code GeocodeResource}), so an address-only meeting place is
 *                 the ordinary output of the evac wizard, not an edge case. A
 *                 household whose places were all address-only was told it had
 *                 none. The row is now returned with {@code mappable=false} so
 *                 the client can list what exists and pin only what it can
 *                 place. Never invent a coordinate to satisfy this flag.
 */
public record MapPlaceDto(
        String id,
        String kind,
        Double lat,
        Double lng,
        String name,
        String address,
        String source,
        boolean mappable,
        /**
         * The meeting place's {@code meetingTier}, verbatim
         * ({@code INDOOR_SAFE_ROOM | OUTSIDE_HOME | OUT_OF_TOWN | OTHER}) — so
         * "first" meeting place is the household's own choice, not list order
         * (BE-5). Null for every source that has no tier.
         */
        String tier,
        /**
         * The row's own {@code deploy} flag — the PLAN'S PRIMARY PICK, not
         * "deployed" (that is {@code role = selected-*}). Meeting places and
         * evacuation plans carry one; null for sources that do not.
         */
        Boolean deploy,
        /**
         * What this place is in the plan (plan-locations audit 2026-09-29):
         * {@code home} · {@code meeting} / {@code shelter} (the plan's primary,
         * no deployment live) · {@code selected-meeting} / {@code selected-shelter}
         * (what the live deployment selected) · {@code start} (a starting point
         * as entered in the plan) · {@code selected-start} (one the live deployment
         * chose, V91). Appended last: positional record.
         */
        String role
) {}
