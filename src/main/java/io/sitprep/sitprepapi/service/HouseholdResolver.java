package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves which household a plan write belongs to — used to stamp
 * {@code householdId} onto plan rows (Phase 2, docs/WIP_HOUSEHOLD_PLANS.md).
 * Centralizes the lookup so the plan services don't each inject
 * {@link UserInfoRepo}.
 *
 * <p>By default a plan belongs to its author's BASE household. For
 * cross-household editing, an admin's editor sends the
 * {@value #EDIT_HOUSEHOLD_HEADER} header naming the household being edited;
 * {@link #writableTargetHousehold(String)} returns it ONLY when the caller
 * may write that household ({@link HouseholdAccessService#canWriteHousehold}).
 * No header → null → callers use the base household (unchanged behavior).</p>
 */
@Service
public class HouseholdResolver {

    /** Header an admin's cross-household editor sends to target a household. */
    public static final String EDIT_HOUSEHOLD_HEADER = "X-Household-Id";

    private final UserInfoRepo userInfoRepo;
    private final HouseholdAccessService householdAccessService;

    public HouseholdResolver(UserInfoRepo userInfoRepo,
                             HouseholdAccessService householdAccessService) {
        this.userInfoRepo = userInfoRepo;
        this.householdAccessService = householdAccessService;
    }

    /** The author's base household id, or null if the user / base is unknown. */
    @Transactional(readOnly = true)
    public String baseHouseholdIdFor(String email) {
        if (email == null || email.isBlank()) return null;
        return userInfoRepo.findByUserEmailIgnoreCase(email.trim())
                .map(UserInfo::getBaseHouseholdId)
                .orElse(null);
    }

    /** The {@value #EDIT_HOUSEHOLD_HEADER} header on the current request, or null. */
    public String editingHouseholdId() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                String h = sra.getRequest().getHeader(EDIT_HOUSEHOLD_HEADER);
                return (h != null && !h.isBlank()) ? h.trim() : null;
            }
        } catch (Exception ignored) {
            // No request context (e.g. a scheduled job) — no override.
        }
        return null;
    }

    /**
     * If this request asked to edit a specific household (via the
     * {@value #EDIT_HOUSEHOLD_HEADER} header) AND {@code email} may write
     * that household, returns the target household id; otherwise null (→ the
     * caller falls back to the author's base household, the default).
     */
    @Transactional(readOnly = true)
    public String writableTargetHousehold(String email) {
        String target = editingHouseholdId();
        if (target == null) return null;
        return householdAccessService.canWriteHousehold(email, target) ? target : null;
    }
}
