package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.RSEvent;
import io.sitprep.sitprepapi.domain.UserInfo;

import java.util.LinkedHashSet;
import java.util.Set;

public final class RSEventMapper {

    private RSEventMapper() {}

    public static RSEventDto toDto(RSEvent e) {
        if (e == null) return null;

        Set<String> attendees = e.getAttendeeEmails() == null
                ? Set.of()
                : new LinkedHashSet<>(e.getAttendeeEmails());

        RSEventDto.RSEventDtoBuilder b = RSEventDto.builder()
                .id(e.getId())
                .groupId(e.getGroupId())
                .title(e.getTitle())
                .eventType(e.getEventType())
                .startsAt(e.getStartsAt())
                .endsAt(e.getEndsAt())
                .locationName(e.getLocationName())
                .address(e.getAddress())
                .latitude(e.getLatitude())
                .longitude(e.getLongitude())
                .recurrenceRule(e.getRecurrenceRule())
                .notes(e.getNotes())
                .cancelled(e.isCancelled())
                .isPublic(e.isPublic())
                .isPrivate(e.getIsPrivate()) // ✅ FIX
                .createdByEmail(e.getCreatedByEmail())
                .attendeeEmails(attendees)
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