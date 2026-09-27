package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Read-shape for one community resource board entry. {@code distanceKm}
 * is null for a national listing (a hotline with no coordinates) and
 * set for a geo-pinned one — the frontend renders "Nationwide" vs
 * "3.2 mi away" off that. The submitter's email is intentionally not
 * exposed.
 */
public record ResourceListingDto(
        Long id,
        String title,
        String description,
        String category,
        Double latitude,
        Double longitude,
        String address,
        String contact,
        String source,
        Double distanceKm,
        Instant createdAt,
        /** The weekly schedule as stored ({@code {tz, weekly, note?}}); null when the listing has none (V84). */
        Map<String, Object> hours,
        /**
         * Server-computed from {@link #hours} at read time. Null when there are
         * no hours (or no ranges on any day) — never assumed open.
         */
        Boolean openNow,
        /** While open: the next closing instant (null when open around the clock). */
        Instant closesAt,
        /** While closed: the next opening instant. */
        Instant opensAt
) {}
