// src/main/java/io/sitprep/sitprepapi/dto/RSEventUpsertRequest.java
package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.RSEventVisibility;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RSEventUpsertRequest {

    // core
    private String groupId;
    private String title;
    private String eventType;
    private Instant startsAt;
    private Instant endsAt;

    private String locationName;
    private String address;
    private Double latitude;
    private Double longitude;

    // recurrence
    private String recurrenceRule;
    private String recurrenceTimezone;
    private String seriesId;
    private Boolean isException;
    private Instant originalStartsAt;

    // join rules
    private Integer maxAttendees;     // null = unlimited
    private Boolean requiresApproval; // null = do not change on update
    private Boolean isFree;           // null = do not change on update
    private Integer costCents;
    private String currency;
    private String skillLevel;
    private Boolean isIndoor;

    // visibility (canonical)
    private RSEventVisibility visibility;

    // legacy toggles (optional; for transitional UI)
    private Boolean isPublic;
    private Boolean isPrivate;

    // meta
    private String notes;
    private Boolean cancelled;
    private String cancelReason;
}