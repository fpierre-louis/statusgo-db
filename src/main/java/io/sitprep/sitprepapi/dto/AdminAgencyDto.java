package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.Group;

public record AdminAgencyDto(
        String groupId,
        String name,
        String kind,
        String ownerEmail,
        String ownerName,
        String logoImageUrl,
        String planTier,
        String subscriptionStatus,
        String subscriptionOverrideTier,
        java.time.Instant subscriptionOverrideExpiresAt,
        boolean agencyAuthorized,
        Double jurisdictionLat,
        Double jurisdictionLng,
        Double jurisdictionRadiusMiles,
        String jurisdictionType,
        String groupUrl
) {
    public static AdminAgencyDto from(Group group) {
        return new AdminAgencyDto(
                group.getGroupId(),
                group.getGroupName(),
                group.getGroupType(),
                group.getOwnerEmail(),
                group.getOwnerName(),
                group.getLogoImageUrl(),
                group.getPlanTier(),
                group.getSubscriptionStatus(),
                group.getSubscriptionOverrideTier(),
                group.getSubscriptionOverrideExpiresAt(),
                group.isAgencyAuthorized(),
                group.getJurisdictionLat(),
                group.getJurisdictionLng(),
                group.getJurisdictionRadiusMiles(),
                group.getJurisdictionType(),
                group.getGroupId() == null ? null : "/groups/" + group.getGroupId()
        );
    }
}
