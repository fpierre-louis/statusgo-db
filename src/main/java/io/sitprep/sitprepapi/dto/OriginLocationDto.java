package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.OriginLocation;

/**
 * Read shape for {@link OriginLocation} (Thin-Client Refactor Phase 3 — DTO
 * hardening). Strips {@code ownerEmail} + {@code householdId}; keeps {@code id}
 * as the FE's row handle for per-id PUT/DELETE.
 */
public record OriginLocationDto(
        Long id,
        String name,
        String address,
        Double lat,
        Double lng
) {
    public static OriginLocationDto from(OriginLocation o) {
        return new OriginLocationDto(
                o.getId(),
                o.getName(),
                o.getAddress(),
                o.getLat(),
                o.getLng());
    }
}
