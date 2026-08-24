package io.sitprep.sitprepapi.dto;

/**
 * One pin on the household map's "places" layer — a unified, typed feed that
 * replaces the frontend's localStorage assembly of home / meeting places /
 * shelters (gap B of docs/MAP_REBUILD_PLAN.md). Served by
 * {@code GET /api/households/{householdId}/map-places}.
 *
 * @param id       stable, source-prefixed id for FE keying/dedup
 *                 (e.g. {@code "group:<gid>"}, {@code "shelter:<id>"})
 * @param kind     render discriminator: {@code house | meetup | shelter | saved}
 * @param lat      latitude (double precision). NULL when the row was saved
 *                 with an address but never geocoded — see {@code mappable}.
 * @param lng      longitude (double precision). NULL under the same condition.
 * @param name     display name
 * @param address  free-form address, may be null
 * @param source   originating table: {@code group | meeting_place |
 *                 evacuation_plan | user_saved_location}
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
        boolean mappable
) {}
