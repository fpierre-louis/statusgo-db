// src/main/java/io/sitprep/sitprepapi/dto/RSEventDto.java
package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.RSAttendanceStatus;
import io.sitprep.sitprepapi.domain.RSEventVisibility;
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

    // keep as String for frontend compatibility during migration
    private String latitude;
    private String longitude;

    // recurrence
    private String recurrenceRule;
    private String recurrenceTimezone;
    private String seriesId;
    private boolean isException;
    private Instant originalStartsAt;

    private String notes;

    // legacy + canonical visibility
    private boolean cancelled;

    private boolean isPublic;
    private boolean isPrivate;
    private RSEventVisibility visibility;

    // join rules
    private Integer maxAttendees;
    private boolean requiresApproval;
    private boolean isFree;
    private Integer costCents;
    private String currency;
    private String skillLevel;
    private Boolean isIndoor;

    // creator
    private String createdByEmail;
    private CreatorDto createdBy;

    // legacy (will phase out) — keep for now so old UI doesn’t break
    private Set<String> attendeeEmails;

    // ✅ new UI helpers (safe additive fields)
    private Integer attendeeCount;           // count of "Going/Maybe" from attendance table
    private RSAttendanceStatus viewerStatus; // GOING/MAYBE/...
    private Boolean viewerCanJoin;           // null if viewer unknown
    private String joinBlockReason;          // ex: "CAPACITY_FULL", "PRIVATE_GROUP", ...

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