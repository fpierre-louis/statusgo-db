package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.constant.PlanTier;
import io.sitprep.sitprepapi.domain.Group;

import java.time.Instant;

public record AdminBillingAgencyDto(
        String groupId,
        String name,
        String ownerEmail,
        String logoImageUrl,
        String planTier,
        String effectivePlanTier,
        String stripeCustomerId,
        String stripeSubscriptionId,
        String subscriptionStatus,
        String overrideTier,
        Instant overrideExpiresAt,
        String overrideReason,
        String overrideBy,
        Instant overrideAt,
        boolean overrideActive
) {
    public static AdminBillingAgencyDto from(Group group, Instant now) {
        boolean active = group.getSubscriptionOverrideTier() != null
                && group.getSubscriptionOverrideExpiresAt() != null
                && group.getSubscriptionOverrideExpiresAt().isAfter(now);
        String base = PlanTier.fromWire(group.getPlanTier()).name();
        String effective = active ? PlanTier.fromWire(group.getSubscriptionOverrideTier()).name() : base;
        return new AdminBillingAgencyDto(
                group.getGroupId(),
                group.getGroupName(),
                group.getOwnerEmail(),
                group.getLogoImageUrl(),
                base,
                effective,
                group.getStripeCustomerId(),
                group.getStripeSubscriptionId(),
                group.getSubscriptionStatus(),
                group.getSubscriptionOverrideTier(),
                group.getSubscriptionOverrideExpiresAt(),
                group.getSubscriptionOverrideReason(),
                group.getSubscriptionOverrideBy(),
                group.getSubscriptionOverrideAt(),
                active
        );
    }
}
