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
        String serviceArea,
        String permanentAddress,
        String temporaryEventAddress,
        boolean emergencyPostingEnabled,
        String groupId,
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
                DtoImages.avatar(u.getProfileImageUrl()),
                u.getVerifiedPublisherKind(),
                u.getVerifiedSince(),
                u.getVerifiedPublisherServiceArea(),
                u.getVerifiedPublisherPermanentAddress(),
                u.getVerifiedPublisherTemporaryEventAddress(),
                u.isVerifiedPublisherEmergencyPostingEnabled(),
                u.getVerifiedPublisherGroupId(),
                u.getLatitude(),
                u.getLongitude(),
                distanceKm
        );
    }
}
