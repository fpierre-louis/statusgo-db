package io.sitprep.sitprepapi.dto;

/**
 * Write-shape for a resident submitting a resource to the community
 * board. {@code latitude/longitude} come from the submitter's current
 * location — someone on the ground reporting a resource is at or near
 * it. All-null coords are allowed (a non-local tip) and produce a
 * national listing.
 */
public record SubmitResourceRequest(
        String title,
        String description,
        String category,
        String address,
        String contact,
        Double latitude,
        Double longitude
) {}
