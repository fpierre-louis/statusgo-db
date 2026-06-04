package io.sitprep.sitprepapi.dto;

public record SubmitVerificationApplicationRequest(
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
        String notes
) {}
