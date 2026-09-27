package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.VerificationApplication;

import java.time.Instant;

/** Public-safe applicant view. Internal reviewer identity and notes are excluded. */
public record AgencyRequestStatusDto(
        Long id,
        String agencyName,
        String status,
        String applicantStatusNote,
        boolean informationRequested,
        String workspaceGroupId,
        String workspacePath,
        Instant submittedAt,
        Instant updatedAt,
        Instant reviewedAt,
        Instant provisionedAt
) {
    public static AgencyRequestStatusDto from(VerificationApplication app) {
        boolean provisioned = app.getStatus() == VerificationApplication.Status.PROVISIONED;
        return new AgencyRequestStatusDto(
                app.getId(), firstPresent(app.getPublicName(), app.getLegalName(), "Agency request"),
                app.getStatus() == null ? null : app.getStatus().name(),
                app.getApplicantStatusNote(),
                app.getStatus() == VerificationApplication.Status.NEEDS_INFO,
                provisioned ? app.getGroupId() : null,
                provisioned && app.getGroupId() != null ? "/groups/" + app.getGroupId() + "/admin" : null,
                app.getSubmittedAt(), app.getUpdatedAt(), app.getReviewedAt(), app.getProvisionedAt());
    }

    private static String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
