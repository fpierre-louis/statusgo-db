package io.sitprep.sitprepapi.dto;

import java.time.Instant;

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
        Instant createdAt
) {}
