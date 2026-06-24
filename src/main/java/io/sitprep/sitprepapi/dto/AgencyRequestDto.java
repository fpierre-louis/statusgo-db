package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.VerificationApplication;

import java.time.Instant;

public record AgencyRequestDto(
        Long id,
        String groupId,
        String groupName,
        String agencyName,
        String officialEmail,
        String contactName,
        String contactRole,
        String message,
        String statedJurisdiction,
        String status,
        String assignedConsultantEmail,
        String submitterEmail,
        String source,
        String verifiedKind,
        String approvedPublisherEmail,
        boolean emergencyPostingEnabled,
        Double draftLat,
        Double draftLng,
        Double draftRadiusMiles,
        Instant createdAt,
        Instant updatedAt,
        Instant submittedAt
) {
    public static AgencyRequestDto from(VerificationApplication app, Group group) {
        return new AgencyRequestDto(
                app.getId(),
                app.getGroupId(),
                group == null ? null : group.getGroupName(),
                firstPresent(app.getPublicName(), app.getLegalName(), group == null ? null : group.getGroupName()),
                app.getOfficialEmail(),
                app.getPrimaryAdmin(),
                app.getBackupContact(),
                app.getPostingIntent(),
                firstPresent(app.getStatedJurisdiction(), app.getAddressOrJurisdiction()),
                app.getStatus() == null ? null : app.getStatus().name(),
                app.getAssignedConsultantEmail(),
                app.getSubmitterEmail(),
                app.getSource(),
                app.getVerifiedKind(),
                app.getApprovedPublisherEmail(),
                app.isEmergencyPostingEnabled(),
                app.getDraftLat(),
                app.getDraftLng(),
                app.getDraftRadiusMiles(),
                app.getCreatedAt(),
                app.getUpdatedAt(),
                app.getSubmittedAt());
    }

    private static String firstPresent(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
