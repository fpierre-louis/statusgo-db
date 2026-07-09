package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.Demographic;

/**
 * Read shape for {@link Demographic} (Thin-Client Refactor Phase 3 — DTO
 * hardening). Strips {@code ownerEmail}, {@code householdId}, and the internal
 * {@code adminEmails} list; exposes only the household head-count the FE renders.
 */
public record DemographicDto(
        Long id,
        int infants,
        int adults,
        int teens,
        int kids,
        int dogs,
        int cats,
        int pets
) {
    public static DemographicDto from(Demographic d) {
        return new DemographicDto(
                d.getId(),
                d.getInfants(),
                d.getAdults(),
                d.getTeens(),
                d.getKids(),
                d.getDogs(),
                d.getCats(),
                d.getPets());
    }
}
