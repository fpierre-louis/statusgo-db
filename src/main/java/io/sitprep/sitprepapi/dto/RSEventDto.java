package io.sitprep.sitprepapi.dto;

import lombok.*;

import java.time.Instant;
import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RSEventDto {

    private String id;
    private String groupId;

    private String title;
    private String eventType;

    private Instant startsAt;
    private Instant endsAt;

    private String locationName;
    private String address;
    private String latitude;
    private String longitude;

    private String recurrenceRule;
    private String notes;

    private boolean cancelled;

    // ✅ NEW
    private boolean isPublic;
    private boolean isPrivate;

    private String createdByEmail;
    private CreatorDto createdBy;

    private Set<String> attendeeEmails;

    private Instant createdAt;
    private Instant updatedAt;


    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreatorDto {
        private String userEmail;
        private String userFirstName;
        private String userLastName;
        private String firebaseUid;
        private String profileImageURL;
        private String title;
    }
}