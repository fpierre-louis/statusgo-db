package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Single source of truth for the "every user has a base household" guarantee
 * (2026-07-02). A user's base household is where their personal plan lives and
 * what the dashboard anchors to — the app assumes it always exists, even for a
 * solo user.
 *
 * <p>Two callers use {@link #ensureBaseHousehold}:
 * <ul>
 *   <li><b>Provisioning</b> — {@code UserInfoService.upsertByFirebaseUid} /
 *       {@code createUser} call it on new-user creation, so the base holds by
 *       construction from the first sign-in.</li>
 *   <li><b>Backfill</b> — {@code HouseholdBackfillService} calls it per-user
 *       (each in its own transaction) to repair existing users who predate the
 *       guarantee. Isolated per-user so one un-loadable household can't block
 *       everyone else's base assignment.</li>
 * </ul>
 *
 * <p>The method is idempotent: a no-op when a base is already set, and it
 * prefers an EXISTING {@code groupType="Household"} group before creating a new
 * personal one — so a user who already owns/belongs to a household never gets a
 * duplicate.</p>
 */
@Service
public class HouseholdProvisioningService {

    private final UserInfoRepo userInfoRepo;
    private final GroupRepo groupRepo;

    public HouseholdProvisioningService(UserInfoRepo userInfoRepo, GroupRepo groupRepo) {
        this.userInfoRepo = userInfoRepo;
        this.groupRepo = groupRepo;
    }

    /**
     * Guarantee that {@code u} has a base household, creating a personal one if
     * needed. Joins the caller's transaction (REQUIRED) so provisioning stays
     * atomic with the user save; the backfill wraps its per-user call in a
     * REQUIRES_NEW template for isolation. Returns the base groupId, or null
     * when the user has no email to key off.
     */
    @Transactional
    public String ensureBaseHousehold(UserInfo u) {
        if (u == null) return null;
        if (u.getBaseHouseholdId() != null && !u.getBaseHouseholdId().isBlank()) {
            return u.getBaseHouseholdId();
        }
        // Guests are ephemeral (purged after expiry) — auto-creating a
        // household for them would orphan the group on purge. They get a base
        // when/if they convert to a real account.
        if (Boolean.TRUE.equals(u.getGuestAccount())) return null;
        String email = u.getUserEmail();
        if (email == null || email.isBlank()) return null;

        Group base = firstHousehold(email);
        if (base == null) base = createPersonalHousehold(u, email);
        if (base == null) return null;

        u.setBaseHouseholdId(base.getGroupId());
        // Keep the denormalized managed-id cache coherent.
        if (u.getManagedGroupIDs() == null) u.setManagedGroupIDs(new HashSet<>());
        u.getManagedGroupIDs().add(base.getGroupId());
        userInfoRepo.save(u);
        return base.getGroupId();
    }

    /** The user's first existing Household group, or null. */
    private Group firstHousehold(String email) {
        return groupRepo.findByMemberEmail(email).stream()
                .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()))
                .findFirst()
                .orElse(null);
    }

    /** Mirrors the fields CreateHouseholdGroup.js sets for a new household. */
    private Group createPersonalHousehold(UserInfo u, String email) {
        Group g = new Group();
        g.setGroupId(UUID.randomUUID().toString());
        String first = u.getUserFirstName();
        String last = u.getUserLastName();
        // Family-name convention: prefer lastName ("the Plouis household"),
        // fall back to firstName, then "My".
        String stem;
        if (last != null && !last.isBlank()) stem = last.trim() + "'s";
        else if (first != null && !first.isBlank()) stem = first.trim() + "'s";
        else stem = "My";
        g.setGroupName(stem + " Household");
        g.setGroupCode("HH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        g.setGroupType("Household");
        g.setPrivacy("Private");
        g.setAlert("Not Active");
        g.setOwnerEmail(email);
        g.setOwnerName(((first == null ? "" : first) + " " + (last == null ? "" : last)).trim());
        g.setAdminEmails(new ArrayList<>(List.of(email)));
        g.setMemberEmails(new ArrayList<>(List.of(email)));
        g.setMemberCount(1);
        Instant now = Instant.now();
        g.setCreatedAt(now);
        g.setUpdatedAt(now);
        return groupRepo.save(g);
    }
}
