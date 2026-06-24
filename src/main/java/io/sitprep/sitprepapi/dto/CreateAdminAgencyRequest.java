package io.sitprep.sitprepapi.dto;

public record CreateAdminAgencyRequest(
        String groupId,
        String groupName,
        String ownerEmail,
        String ownerName,
        String logoImageUrl,
        String kind,
        String jurisdictionType,
        Double lat,
        Double lng,
        Double radiusMiles
) {}
