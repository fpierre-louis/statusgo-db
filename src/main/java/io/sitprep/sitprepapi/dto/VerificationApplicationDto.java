package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.VerificationApplication;

import java.time.Instant;

public record VerificationApplicationDto(
        Long id,
        String groupId,
        String groupName,
        String groupType,
        int authorizedAdminCount,
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
        String publisherServiceArea,
        String publisherPermanentAddress,
        String publisherTemporaryEventAddress,
        boolean emergencyPostingEnabled,
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
                authorizedAdminCount(group),
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
                app.getPublisherServiceArea(),
                app.getPublisherPermanentAddress(),
                app.getPublisherTemporaryEventAddress(),
                app.isEmergencyPostingEnabled(),
                app.getCreatedAt(),
                app.getUpdatedAt(),
                app.getSubmittedAt(),
                app.getReviewedAt()
        );
    }

    private static int authorizedAdminCount(Group group) {
        if (group == null) return 0;
        java.util.Set<String> emails = new java.util.HashSet<>();
        add(emails, group.getOwnerEmail());
        if (group.getAdminEmails() != null) {
            for (String email : group.getAdminEmails()) add(emails, email);
        }
        return emails.size();
    }

    private static void add(java.util.Set<String> emails, String email) {
        if (email == null || email.isBlank()) return;
        emails.add(email.trim().toLowerCase(java.util.Locale.ROOT));
    }
}
