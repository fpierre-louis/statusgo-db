package io.sitprep.sitprepapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** Wire shapes for "Still here?" (V85, map-ideal BE-7). */
public final class MapConfirmationDtos {
    private MapConfirmationDtos() {}

    /**
     * {@code POST /api/map/confirmations} body. {@code targetType} is
     * {@code resource | post | osm}; {@code targetId} is the bare id —
     * {@code "42"} (resource listing), {@code "123"} (community post, i.e.
     * {@code MapPoiDto.postId}), {@code "node/123"} (OSM; the part of an
     * Overpass {@code MapPoiDto.id} after {@code "overpass:"}).
     */
    public record ConfirmRequest(String targetType, String targetId) {}

    /**
     * 200 and 429 body. {@code count} = distinct people in the last 7 days
     * (including the caller); {@code lastAt} = the latest of them;
     * {@code mine} = the caller has confirmed. On 429 only,
     * {@code retryAfterSeconds} says when the caller may confirm again (also
     * sent as {@code Retry-After}).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConfirmResponse(long count, Instant lastAt, boolean mine, Long retryAfterSeconds) {}

    /**
     * The read shape on {@code ResourceListingDto.confirmations} and
     * {@code MapPoiDto.confirmations}: distinct people in the last 7 days and
     * the latest time. The field itself is null when that count is zero.
     */
    public record ConfirmationSummary(long count, Instant lastAt) {}
}
