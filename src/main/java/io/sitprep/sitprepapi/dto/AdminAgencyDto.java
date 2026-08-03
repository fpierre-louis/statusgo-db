package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.Group;

import java.util.List;

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
        String groupUrl,
        /**
         * The agency's admin roster — needed by the platform console to render
         * and manage admins (Lane B2 of
         * docs/audits/2026-07-30-agency-admin-management/FINDINGS.md). Before
         * this the console had the authority to manage agencies but no roster
         * data to act on, so the only visible identity was {@link #ownerEmail}.
         *
         * <p>Never null — an agency with no admin rows ships an empty list.</p>
         *
         * <p><b>memberEmails is deliberately NOT shipped here.</b> The console
         * manages ADMINS, not members; adding the member roster would bulk-ship
         * every member address of every agency on a list endpoint that is
         * fetched in full on every visit to the Agencies tab. If a per-agency
         * member roster is ever needed, it belongs on a detail endpoint, not
         * this one.</p>
         */
        List<String> adminEmails
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
                group.getGroupId() == null ? null : "/groups/" + group.getGroupId(),
                // Defensive copy of an EAGER @ElementCollection — never hand a
                // Hibernate-managed PersistentBag out on a DTO. Nulls are
                // filtered rather than copied: legacy rows can carry them (every
                // GroupService roster walk is null-guarded for this reason) and
                // List.copyOf would NPE on one.
                group.getAdminEmails() == null
                        ? List.of()
                        : group.getAdminEmails().stream()
                                .filter(e -> e != null && !e.isBlank())
                                .toList()
        );
    }
}
