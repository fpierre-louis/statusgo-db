package io.sitprep.sitprepapi.dto;

/**
 * Sanitized DTO for typeahead user search results.
 *
 * <p>Deliberately omits phone, address, lat/lng, FCM token, household
 * memberships, alert preferences, and every other field on
 * {@code UserInfo} — search results expose only the minimum signal a
 * caller needs to decide "is this the right person to invite?":
 * a display name, a profile photo, and the email handle the invite
 * targets.</p>
 *
 * <p>Privacy contract locked in {@code docs/HOME_HOUSEHOLD_MERGE.md} §5.
 * Any field added to this DTO requires updating that contract first.</p>
 */
public record UserSearchDto(
        String firstName,
        String lastName,
        String email,
        String profileImageUrl
) {}
