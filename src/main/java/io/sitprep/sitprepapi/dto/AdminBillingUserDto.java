package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.UserInfo;

import java.time.Instant;

public record AdminBillingUserDto(
        String email,
        String name,
        String subscription,
        String subscriptionPackage,
        String overridePackage,
        Instant overrideExpiresAt,
        String overrideReason,
        String overrideBy,
        Instant overrideAt,
        boolean overrideActive
) {
    public static AdminBillingUserDto from(UserInfo user, Instant now) {
        boolean active = user.getSubscriptionOverridePackage() != null
                && user.getSubscriptionOverrideExpiresAt() != null
                && user.getSubscriptionOverrideExpiresAt().isAfter(now);
        String name = String.join(" ",
                user.getUserFirstName() == null ? "" : user.getUserFirstName(),
                user.getUserLastName() == null ? "" : user.getUserLastName()).trim();
        return new AdminBillingUserDto(
                user.getUserEmail(),
                name.isBlank() ? null : name,
                user.getSubscription(),
                user.getSubscriptionPackage(),
                user.getSubscriptionOverridePackage(),
                user.getSubscriptionOverrideExpiresAt(),
                user.getSubscriptionOverrideReason(),
                user.getSubscriptionOverrideBy(),
                user.getSubscriptionOverrideAt(),
                active
        );
    }
}
