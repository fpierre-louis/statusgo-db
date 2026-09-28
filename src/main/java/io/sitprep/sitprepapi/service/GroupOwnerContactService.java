package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * A group owner's phone, for the people who belong — backs
 * {@code GET /api/groups/{groupId}/owner-contact}.
 *
 * <p>Built 2026-09-27 so {@code phone} could leave the cross-user
 * {@code /api/userinfo/email/{email}} lookup, where any signed-in user could
 * read any user's number (docs/epics/privacy-push-token-and-phone/EXEC.md).
 * MapView's subgroup "Call owner" was the reader that kept it there.</p>
 *
 * <p>Who may read it: a member (owner, admin or member) of the group itself,
 * or of a PARENT group — where the link exists on both sides: the parent lists
 * the group in {@code subGroupIDs} AND the group lists the parent in
 * {@code parentGroupIDs}. One side is not enough: anyone can create a group
 * and write a stranger's group id into its {@code subGroupIDs}, which would
 * hand that stranger's phone to everyone in the attacker's group. The group's
 * own admins control {@code parentGroupIDs}, so requiring it is consent.</p>
 */
@Service
public class GroupOwnerContactService {

    public record OwnerContact(String ownerEmail, String phone) {}

    private final GroupRepo groupRepo;
    private final UserInfoRepo userInfoRepo;

    public GroupOwnerContactService(GroupRepo groupRepo, UserInfoRepo userInfoRepo) {
        this.groupRepo = groupRepo;
        this.userInfoRepo = userInfoRepo;
    }

    /**
     * Empty when the group is unknown OR the caller may not see it — the two
     * are deliberately indistinguishable (the resource answers 404 for both).
     * A present contact may still carry a null phone: no number on file.
     */
    @Transactional(readOnly = true)
    public Optional<OwnerContact> ownerContact(String groupId, String callerEmail) {
        if (groupId == null || groupId.isBlank() || callerEmail == null || callerEmail.isBlank()) {
            return Optional.empty();
        }
        Group group = groupRepo.findByGroupId(groupId.trim()).orElse(null);
        if (group == null) return Optional.empty();
        if (!isMemberOf(group, callerEmail) && !isMemberOfALinkedParent(group, callerEmail)) {
            return Optional.empty();
        }
        String owner = group.getOwnerEmail();
        if (owner == null || owner.isBlank()) return Optional.of(new OwnerContact(null, null));
        String phone = userInfoRepo.findByUserEmailIgnoreCase(owner.trim())
                .map(UserInfo::getPhone)
                .map(String::trim)
                .filter(p -> !p.isEmpty())
                .orElse(null);
        return Optional.of(new OwnerContact(owner.trim().toLowerCase(), phone));
    }

    private boolean isMemberOfALinkedParent(Group group, String email) {
        List<String> parents = group.getParentGroupIDs();
        if (parents == null || parents.isEmpty()) return false;
        for (String parentId : parents) {
            if (parentId == null || parentId.isBlank()) continue;
            Group parent = groupRepo.findByGroupId(parentId.trim()).orElse(null);
            if (parent == null) continue;
            boolean parentListsChild = containsIgnoreCase(parent.getSubGroupIDs(), group.getGroupId());
            if (parentListsChild && isMemberOf(parent, email)) return true;
        }
        return false;
    }

    // Same rule as PostReadAuthorizer.isMemberOf / GroupPostService.isMemberOf.
    private static boolean isMemberOf(Group group, String email) {
        if (group.getOwnerEmail() != null && group.getOwnerEmail().equalsIgnoreCase(email.trim())) {
            return true;
        }
        return containsIgnoreCase(group.getAdminEmails(), email)
                || containsIgnoreCase(group.getMemberEmails(), email);
    }

    private static boolean containsIgnoreCase(Collection<String> values, String value) {
        if (values == null || value == null) return false;
        String v = value.trim();
        return values.stream().anyMatch(x -> x != null && x.trim().equalsIgnoreCase(v));
    }
}
