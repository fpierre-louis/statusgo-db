package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.UserInfo;

import java.time.Instant;

/**
 * Public-facing wire shape for a verified publisher. Returned by the
 * radius discovery endpoint + admin verify endpoint. Never includes
 * sensitive fields (no FCM tokens, no joined-group lists, no emails
 * beyond the publisher's own address since that's how their identity
 * is keyed).
 */
public record VerifiedPublisherDto(
        String email,
        String displayName,
        String profileImageUrl,
        String kind,
        Instant verifiedSince,
        Double latitude,
        Double longitude,
        /** Distance from query point — only set on the radius endpoint; null otherwise. */
        Double distanceKm
) {

    public static VerifiedPublisherDto fromEntity(UserInfo u, Double distanceKm) {
        String name = ((u.getUserFirstName() == null ? "" : u.getUserFirstName()) + " "
                + (u.getUserLastName() == null ? "" : u.getUserLastName())).trim();
        return new VerifiedPublisherDto(
                u.getUserEmail(),
                name.isBlank() ? u.getUserEmail() : name,
                u.getProfileImageURL(),
                u.getVerifiedPublisherKind(),
                u.getVerifiedSince(),
                parseDoubleOrNull(u.getLatitude()),
                parseDoubleOrNull(u.getLongitude()),
                distanceKm
        );
    }

    /**
     * UserInfo stores lat/lng as String columns (legacy schema). Parse
     * to Double here so the wire shape is numeric, which matches what
     * every FE consumer expects.
     */
    private static Double parseDoubleOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }
}
