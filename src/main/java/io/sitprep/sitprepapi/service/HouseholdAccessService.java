package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.repo.GroupRepo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * "Can A read B's plan data?" helper. The household plan-sharing flow
 * needs members to be able to view the household head's emergency
 * contacts, meal plan, and evacuation plans, but we don't want any
 * signed-in user to read any other signed-in user's plans.
 *
 * <p>The relationship that grants access is co-membership in any
 * {@code groupType = "Household"} group. Self-access is always allowed.</p>
 *
 * <p>Cached at the request scope by JPA's first-level cache; if read
 * pressure becomes a concern, add a small per-pair memoization here. For
 * beta-tester scale the per-call query is fine.</p>
 */
@Service
public class HouseholdAccessService {

    private final GroupRepo groupRepo;

    public HouseholdAccessService(GroupRepo groupRepo) {
        this.groupRepo = groupRepo;
    }

    /**
     * True iff {@code caller} and {@code target} are the same person OR
     * share at least one household group. Email comparison is
     * case-insensitive.
     */
    @Transactional(readOnly = true)
    public boolean canReadPlanDataFor(String caller, String target) {
        if (caller == null || target == null) return false;
        String a = caller.trim().toLowerCase(Locale.ROOT);
        String b = target.trim().toLowerCase(Locale.ROOT);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        return sharesHousehold(a, b);
    }

    /**
     * Asserts the caller can read the target's plan data. Throws 403
     * otherwise. Designed to be called inline from each plan-data GET.
     */
    public void requireCanReadPlanDataFor(String caller, String target) {
        if (!canReadPlanDataFor(caller, target)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Plan data is not shared with you");
        }
    }

    /**
     * True iff {@code caller} is a member of the given household group.
     * The household-id-keyed counterpart to {@link #canReadPlanDataFor}
     * — used once plan resolution flips from ownerEmail to householdId
     * (Phase 2). Members can read; see {@link #canWriteHousehold}.
     */
    @Transactional(readOnly = true)
    public boolean canReadHousehold(String caller, String householdId) {
        Group g = household(householdId);
        return g != null && containsIgnoreCase(g.getMemberEmails(), caller);
    }

    /** Asserts the caller can read the household's plan. Throws 403 otherwise. */
    public void requireCanReadHousehold(String caller, String householdId) {
        if (!canReadHousehold(caller, householdId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This household's plan is not shared with you");
        }
    }

    /**
     * True iff {@code caller} is an admin or the owner of the given
     * household group. Only admins co-edit a household's shared plan.
     */
    @Transactional(readOnly = true)
    public boolean canWriteHousehold(String caller, String householdId) {
        Group g = household(householdId);
        if (g == null || caller == null) return false;
        String c = caller.trim().toLowerCase(Locale.ROOT);
        if (c.isEmpty()) return false;
        if (g.getOwnerEmail() != null
                && c.equals(g.getOwnerEmail().trim().toLowerCase(Locale.ROOT))) return true;
        return containsIgnoreCase(g.getAdminEmails(), caller);
    }

    /** Throws 403 when {@link #canWriteHousehold} would return false. */
    @Transactional(readOnly = true)
    public void requireCanAdminHousehold(String caller, String householdId) {
        if (!canWriteHousehold(caller, householdId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "Admin access required for household " + householdId
            );
        }
    }

    /** Loads a group by id, returning null unless it's a Household. */
    private Group household(String householdId) {
        if (householdId == null || householdId.isBlank()) return null;
        return groupRepo.findByGroupId(householdId)
                .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()))
                .orElse(null);
    }

    private static boolean containsIgnoreCase(List<String> emails, String target) {
        if (emails == null || target == null) return false;
        String t = target.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return false;
        for (String e : emails) {
            if (e != null && e.trim().toLowerCase(Locale.ROOT).equals(t)) return true;
        }
        return false;
    }

    private boolean sharesHousehold(String a, String b) {
        // Find every household A is in; check whether any contains B.
        // findByMemberEmail uses LOWER() so A's case doesn't matter; we
        // lower-case B before scanning the member list to match.
        List<Group> aHouseholds = groupRepo.findByMemberEmail(a).stream()
                .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()))
                .toList();
        if (aHouseholds.isEmpty()) return false;

        for (Group g : aHouseholds) {
            List<String> members = g.getMemberEmails();
            if (members == null) continue;
            for (String m : members) {
                if (m == null) continue;
                if (Objects.equals(m.toLowerCase(Locale.ROOT), b)) return true;
            }
        }
        return false;
    }
}
