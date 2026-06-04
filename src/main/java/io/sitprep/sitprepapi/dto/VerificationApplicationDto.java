package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.VerificationApplication;

import java.time.Instant;

public record VerificationApplicationDto(
        Long id,
        String groupId,
        String groupName,
        String groupType,
        String applicantEmail,
        String accountType,
        String legalName,
        String publicName,
        String website,
        String officialEmail,
        String addressOrJurisdiction,
        String serviceArea,
        String primaryAdmin,
        String backupContact,
        String postingIntent,
        String proofLinks,
        String notes,
        String status,
        String reviewerNotes,
        String reviewerEmail,
        String verifiedKind,
        String approvedPublisherEmail,
        Instant createdAt,
        Instant updatedAt,
        Instant submittedAt,
        Instant reviewedAt
) {
    public static VerificationApplicationDto from(VerificationApplication app, Group group) {
        return new VerificationApplicationDto(
                app.getId(),
                app.getGroupId(),
                group == null ? null : group.getGroupName(),
                group == null ? null : group.getGroupType(),
                app.getApplicantEmail(),
                app.getAccountType(),
                app.getLegalName(),
                app.getPublicName(),
                app.getWebsite(),
                app.getOfficialEmail(),
                app.getAddressOrJurisdiction(),
                app.getServiceArea(),
                app.getPrimaryAdmin(),
                app.getBackupContact(),
                app.getPostingIntent(),
                app.getProofLinks(),
                app.getNotes(),
                app.getStatus() == null ? null : app.getStatus().name(),
                app.getReviewerNotes(),
                app.getReviewerEmail(),
                app.getVerifiedKind(),
                app.getApprovedPublisherEmail(),
                app.getCreatedAt(),
                app.getUpdatedAt(),
                app.getSubmittedAt(),
                app.getReviewedAt()
        );
    }
}
