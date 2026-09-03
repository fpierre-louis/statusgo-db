package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.constant.GroupRole;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.MemberStatusFrame;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.service.UserInfoService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

/**
 * An admin answering FOR a member.
 *
 * <pre>
 *   POST /api/groups/{groupId}/members/status   { email, status }
 * </pre>
 *
 * <h4>Why this exists — the control shipped without it</h4>
 * {@code MapView}, {@code SubGroups} and {@code IndividualUser} have all
 * offered "set this member's status" for a long time, and all three wrote it
 * through {@code PATCH /userinfo/{id}} — which calls {@code ensureOwns(id)} and
 * is therefore <b>self-only</b>. Every one of those controls 403s. Three
 * surfaces have been drawing a button the server refuses.
 *
 * <h4>Group-scoped, not household-scoped</h4>
 * A household IS a {@code groupType: "Household"} group, so one route serves
 * the household drawer and the three group surfaces above. A household-only
 * endpoint would have left them broken.
 *
 * <h4>It is a PROXY report and the record says so</h4>
 * The write stamps {@code statusSetByEmail}, so a roster can distinguish
 * "Maya replied" from "Dione answered for Maya". Without that the board reads
 * "Checked in 4m ago" about somebody nobody has heard from — which is the
 * failure the whole check-in surface exists to prevent, produced by the
 * feature meant to help it.
 */
@RestController
@RequestMapping("/api/groups/{groupId}/members")
public class GroupMemberStatusResource {

    private final UserInfoService userInfoService;
    private final GroupRepo groupRepo;

    public GroupMemberStatusResource(UserInfoService userInfoService, GroupRepo groupRepo) {
        this.userInfoService = userInfoService;
        this.groupRepo = groupRepo;
    }

    public record SetMemberStatusRequest(String email, String status) {}

    /**
     * @return the same {@link MemberStatusFrame} the socket broadcasts, so the
     *   caller re-renders from the response instead of refetching.
     */
    @PostMapping("/status")
    public ResponseEntity<MemberStatusFrame> setMemberStatus(
            @PathVariable String groupId,
            @RequestBody SetMemberStatusRequest body
    ) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        if (body == null || body.email() == null || body.email().isBlank()
                || body.status() == null || body.status().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String subject = body.email().trim().toLowerCase(Locale.ROOT);

        Group group = groupRepo.findByGroupId(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found"));
        if (!GroupRole.fromGroup(group, caller).isAtLeastAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Group admin or owner role required");
        }
        // THE SUBJECT HAS TO BE IN THIS GROUP. Without it, admin of any circle
        // is admin of everyone's status: the role check would pass on a group
        // the caller runs while the email names somebody who has never been in
        // it.
        if (!containsIgnoreCase(group.getMemberEmails(), subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "That person is not in this group");
        }

        try {
            return ResponseEntity.ok(userInfoService.setStatusForMember(subject, body.status(), caller));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    private static boolean containsIgnoreCase(List<String> emails, String target) {
        if (emails == null || target == null) return false;
        for (String e : emails) {
            if (e != null && e.trim().equalsIgnoreCase(target)) return true;
        }
        return false;
    }
}
