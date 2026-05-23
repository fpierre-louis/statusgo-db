package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a user's base household id by email — used to stamp
 * {@code householdId} onto plan rows at write time (Phase 2,
 * docs/WIP_HOUSEHOLD_PLANS.md). Centralizes the lookup so the six plan
 * services don't each inject {@link UserInfoRepo}.
 *
 * <p>Today the household a plan belongs to is derived from its author's
 * base household. When the frontend starts editing a specific non-base
 * household's plan, the write paths can instead carry an explicit
 * householdId (gated by {@code HouseholdAccessService.canWriteHousehold});
 * this resolver stays the default.</p>
 */
@Service
public class HouseholdResolver {

    private final UserInfoRepo userInfoRepo;

    public HouseholdResolver(UserInfoRepo userInfoRepo) {
        this.userInfoRepo = userInfoRepo;
    }

    /** The author's base household id, or null if the user / base is unknown. */
    @Transactional(readOnly = true)
    public String baseHouseholdIdFor(String email) {
        if (email == null || email.isBlank()) return null;
        return userInfoRepo.findByUserEmailIgnoreCase(email.trim())
                .map(UserInfo::getBaseHouseholdId)
                .orElse(null);
    }
}
