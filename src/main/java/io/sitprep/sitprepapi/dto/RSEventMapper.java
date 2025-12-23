package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.RSEvent;
import io.sitprep.sitprepapi.domain.RSEventVisibility;
import io.sitprep.sitprepapi.domain.RSAttendanceStatus;
import io.sitprep.sitprepapi.domain.UserInfo;
import org.hibernate.Hibernate;

import java.util.LinkedHashSet;
import java.util.Set;

public final class RSEventMapper {

    private RSEventMapper() {}

    public static RSEventDto toDto(RSEvent e) {
        return toDto(e, null, null, null, null, null);
    }

    public static RSEventDto toDto(
            RSEvent e,
            Integer attendeeCount,
            RSAttendanceStatus viewerStatus,
            Boolean viewerCanJoin,
            String joinBlockReason
    ) {
        return toDto(e, attendeeCount, viewerStatus, viewerCanJoin, joinBlockReason, null);
    }

    /**
     * ✅ Safe mapping that never triggers lazy-loading on legacy attendeeEmails.
     * If attendeeEmailsOverride is provided (recommended for feed/list),
     * the mapper uses that instead of touching e.getAttendeeEmails().
     */
    public static RSEventDto toDto(
            RSEvent e,
            Integer attendeeCount,
            RSAttendanceStatus viewerStatus,
            Boolean viewerCanJoin,
            String joinBlockReason,
            Set<String> attendeeEmailsOverride
    ) {
        if (e == null) return null;

        Set<String> attendeesLegacy;
        if (attendeeEmailsOverride != null) {
            attendeesLegacy = new LinkedHashSet<>(attendeeEmailsOverride);
        } else {
            // ✅ Never force-load ElementCollection (prevents "no Session" in prod)
            if (Hibernate.isInitialized(e.getAttendeeEmails()) && e.getAttendeeEmails() != null) {
                attendeesLegacy = new LinkedHashSet<>(e.getAttendeeEmails());
            } else {
                attendeesLegacy = Set.of();
            }
        }

        // ✅ Canonical visibility (fallback to GROUP_ONLY)
        RSEventVisibility visibility = e.getVisibility() == null
                ? RSEventVisibility.GROUP_ONLY
                : e.getVisibility();

        // ✅ Keep legacy booleans in DTO consistent (derived from visibility)
        boolean isPublic = visibility == RSEventVisibility.DISCOVERABLE_PUBLIC;
        boolean isPrivate = visibility == RSEventVisibility.INVITE_ONLY;

        RSEventDto.RSEventDtoBuilder b = RSEventDto.builder()
                .id(e.getId())
                .groupId(e.getGroupId())

                .title(e.getTitle())
                .eventType(e.getEventType())

                .startsAt(e.getStartsAt())
                .endsAt(e.getEndsAt())

                .locationName(e.getLocationName())
                .address(e.getAddress())

                .latitude(e.getLatitude() == null ? null : String.valueOf(e.getLatitude()))
                .longitude(e.getLongitude() == null ? null : String.valueOf(e.getLongitude()))

                // recurrence
                .recurrenceRule(e.getRecurrenceRule())
                .recurrenceTimezone(e.getRecurrenceTimezone())
                .seriesId(e.getSeriesId())
                .isException(e.isException())
                .originalStartsAt(e.getOriginalStartsAt())

                .notes(e.getNotes())

                // legacy + canonical visibility
                .cancelled(e.isCancelled())
                .visibility(visibility)
                .isPublic(isPublic)
                .isPrivate(isPrivate)

                // join rules
                .maxAttendees(e.getMaxAttendees())
                .requiresApproval(e.isRequiresApproval())
                .isFree(e.isFree())
                .costCents(e.getCostCents())
                .currency(e.getCurrency())
                .skillLevel(e.getSkillLevel())
                .isIndoor(e.getIsIndoor())

                // creator
                .createdByEmail(e.getCreatedByEmail())
                .attendeeEmails(attendeesLegacy)

                // UI helpers
                .attendeeCount(attendeeCount)
                .viewerStatus(viewerStatus)
                .viewerCanJoin(viewerCanJoin)
                .joinBlockReason(joinBlockReason)

                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt());

        UserInfo u = e.getCreatedByUserInfo();
        if (u != null) {
            b.createdBy(RSEventDto.CreatorDto.builder()
                    .userEmail(u.getUserEmail())
                    .userFirstName(u.getUserFirstName())
                    .userLastName(u.getUserLastName())
                    .firebaseUid(u.getFirebaseUid())
                    .profileImageURL(u.getProfileImageURL())
                    .title(u.getTitle())
                    .build());
        }

        return b.build();
    }
}