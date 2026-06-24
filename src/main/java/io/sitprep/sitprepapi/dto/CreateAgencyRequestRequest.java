package io.sitprep.sitprepapi.dto;

public record CreateAgencyRequestRequest(
        String officialEmail,
        String agencyName,
        String contactName,
        String role,
        String message,
        String statedJurisdiction
) {}
