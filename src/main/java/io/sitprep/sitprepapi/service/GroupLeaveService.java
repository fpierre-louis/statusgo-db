package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;

/**
 * A member leaves a group — backs {@code POST /api/groups/{groupId}/leave}.
 *
 * <p>There was no way to do this (2026-09-28): the app PUT the whole group
 * minus the caller, and {@code PUT /groups/{id}} is admin-only, so a plain
 * member got a 403 — and My Groups' long-press "Leave group" only closed its
 * sheet. docs/epics/groups-admin-fixes/EXEC.md in the frontend repo.</p>
 *
 * <ul>
 *   <li>The owner cannot leave (409): transfer ownership or delete first — a
 *       group with no owner has nobody who can manage it.</li>
 *   <li>Not a member or admin → 404, the same answer as an unknown group.</li>
 *   <li>Leaving your BASE household re-anchors you: the base is cleared and
 *       re-provisioned (another household you belong to, else a new personal
 *       one). Left pointing at the old id, {@code /api/me} would keep reading
 *       that household's plans.</li>
 * </ul>
 */
@Service
public class GroupLeaveService {

    private final GroupRepo groupRepo;
    private final UserInfoRepo userInfoRepo;
    private final GroupService groupService;
    private final HouseholdProvisioningService householdProvisioning;

    public GroupLeaveService(GroupRepo groupRepo, UserInfoRepo userInfoRepo,
                             GroupService groupService, HouseholdProvisioningService householdProvisioning) {
        this.groupRepo = groupRepo;
        this.userInfoRepo = userInfoRepo;
        this.groupService = groupService;
        this.householdProvisioning = householdProvisioning;
    }

    @Transactional
    public void leave(String groupId, String callerEmail) {
        if (groupId == null || groupId.isBlank() || callerEmail == null || callerEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String email = callerEmail.trim().toLowerCase();
        Group g = groupRepo.findByGroupId(groupId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (g.getOwnerEmail() != null && g.getOwnerEmail().trim().equalsIgnoreCase(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The owner can't leave. Transfer ownership or delete the group first.");
        }
        if (!contains(g.getMemberEmails(), email) && !contains(g.getAdminEmails(), email)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        groupService.removeMember(g.getGroupId(), email);

        userInfoRepo.findByUserEmailIgnoreCase(email).ifPresent(u -> {
            if (g.getGroupId().equals(u.getBaseHouseholdId())) {
                u.setBaseHouseholdId(null);
                userInfoRepo.save(u);
                householdProvisioning.ensureBaseHousehold(u);
            }
        });
    }

    private static boolean contains(Collection<String> emails, String email) {
        return emails != null && emails.stream().anyMatch(e -> e != null && e.trim().equalsIgnoreCase(email));
    }
}
