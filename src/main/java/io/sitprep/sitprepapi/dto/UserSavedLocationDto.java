package io.sitprep.sitprepapi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.sitprep.sitprepapi.domain.UserSavedLocation;

/**
 * Read shape for {@link UserSavedLocation} (Thin-Client Refactor Phase 3 — DTO
 * hardening). Strips {@code ownerEmail} (PII), the audit fields
 * ({@code createdAt}/{@code updatedAt}), and the internal {@code zipBucket}
 * community pre-filter.
 *
 * <p><b>Serialization quirk (SYSTEM_TRAPS T-9):</b> the entity's Lombok
 * bean-getter {@code isHome()} serializes as {@code "home"} (Jackson strips the
 * "is" prefix from boolean getters), but the FE reads {@code .isHome}. The
 * record component is therefore pinned with {@link JsonProperty} so the wire
 * key is unambiguously {@code isHome} regardless of Jackson's record-vs-bean
 * naming heuristics.</p>
 */
public record UserSavedLocationDto(
        Long id,
        String name,
        String address,
        Double latitude,
        Double longitude,
        @JsonProperty("isHome") boolean isHome,
        String city,
        String region,
        String state,
        String country
) {
    public static UserSavedLocationDto from(UserSavedLocation l) {
        return new UserSavedLocationDto(
                l.getId(),
                l.getName(),
                l.getAddress(),
                l.getLatitude(),
                l.getLongitude(),
                l.isHome(),
                l.getCity(),
                l.getRegion(),
                l.getState(),
                l.getCountry());
    }
}
