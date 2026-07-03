package io.sitprep.sitprepapi.dto;

/**
 * One pin on the household map's "places" layer — a unified, typed feed that
 * replaces the frontend's localStorage assembly of home / meeting places /
 * shelters (gap B of docs/MAP_REBUILD_PLAN.md). Served by
 * {@code GET /api/households/{householdId}/map-places}.
 *
 * @param id      stable, source-prefixed id for FE keying/dedup
 *                (e.g. {@code "group:<gid>"}, {@code "shelter:<id>"})
 * @param kind    render discriminator: {@code house | meetup | shelter | saved}
 * @param lat     latitude (double precision)
 * @param lng     longitude (double precision)
 * @param name    display name
 * @param address free-form address, may be null
 * @param source  originating table: {@code group | meeting_place |
 *                evacuation_plan | user_saved_location}
 */
public record MapPlaceDto(
        String id,
        String kind,
        Double lat,
        Double lng,
        String name,
        String address,
        String source
) {}
