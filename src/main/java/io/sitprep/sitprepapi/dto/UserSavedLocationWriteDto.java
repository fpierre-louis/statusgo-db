package io.sitprep.sitprepapi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.sitprep.sitprepapi.domain.UserSavedLocation;

/**
 * The client-settable half of a saved location.
 *
 * <h2>Why this exists — a field that could never be set</h2>
 *
 * The write endpoints used to bind the raw {@link UserSavedLocation} entity.
 * Its flag is declared {@code private boolean isHome}, so Lombok generates
 * {@code isHome()} / {@code setHome()} and Jackson's bean property is
 * <b>{@code home}</b>. The READ dto pins {@code @JsonProperty("isHome")}. So a
 * client that read {@code isHome} and wrote it back was ignored —
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} is off by Spring Boot default, so no error
 * either.
 *
 * <p>Measured, not inferred: deserializing the entity reports its known
 * properties as {@code [... id, home, city ...]} — {@code isHome} is not among
 * them.</p>
 *
 * <p><b>The consequence was total.</b> Every {@code setHome(true)} in
 * {@code UserSavedLocationService} is gated on {@code incoming.isHome()}, which
 * was always false, so <b>no row could ever become home</b>. Both frontend
 * writers send {@code isHome} — the onboarding wizard's "Home" and the feed's
 * "first save becomes Home" — and both were silently dropped. Downstream,
 * {@code homeFor()} always returned empty, which quietly degraded
 * {@code RiskProfileService}, {@code HouseholdReadinessService} and
 * {@code MeetingPlaceResource}.
 *
 * <h2>Two things a DTO fixes that an annotation would not</h2>
 *
 * Pinning {@code @JsonProperty} on the entity field would fix the name. It
 * would leave the endpoints binding an ENTITY, which also exposes {@code id},
 * {@code ownerEmail}, {@code createdAt}, {@code updatedAt} and the
 * server-derived reverse-geocode columns to the request body. The resource
 * overrides {@code ownerEmail} defensively; nothing guarded the rest.
 *
 * <p>And {@code isHome} here is a <b>nullable {@link Boolean}</b>, which the
 * primitive on the entity cannot be. That distinction is load-bearing on PUT:
 * the update is partial (every other field applies only when non-null), so a
 * body that omits the flag must mean "leave it alone". A primitive would arrive
 * as {@code false} and <b>demote the user's home</b> on any partial edit — a
 * bug the old code could not hit only because the flag never bound at all.</p>
 */
public record UserSavedLocationWriteDto(
        String name,
        String address,
        Double latitude,
        Double longitude,
        /** Null means "unchanged" on update, and "not home" on create. */
        @JsonProperty("isHome") Boolean isHome
) {
    /** A new row for {@code ownerEmail}. Server-derived columns stay unset. */
    public UserSavedLocation toNewEntity(String ownerEmail) {
        UserSavedLocation e = new UserSavedLocation();
        e.setOwnerEmail(ownerEmail);
        e.setName(name);
        e.setAddress(address);
        e.setLatitude(latitude);
        e.setLongitude(longitude);
        e.setHome(Boolean.TRUE.equals(isHome));
        return e;
    }

    /** True only when the caller explicitly asked for a home change. */
    public boolean touchesHome() {
        return isHome != null;
    }
}
