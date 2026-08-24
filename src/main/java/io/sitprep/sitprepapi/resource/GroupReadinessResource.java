package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.constant.GroupRole;
import io.sitprep.sitprepapi.dto.GroupReadinessSummaryDto;
import io.sitprep.sitprepapi.service.GroupReadinessService;
import io.sitprep.sitprepapi.service.GroupService;
import io.sitprep.sitprepapi.util.AuthUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupReadinessResource {

    private final GroupReadinessService groupReadinessService;
    private final GroupService groupService;

    /**
     * GET /api/groups/{groupId}/readiness-summary
     *
     * Returns a high-level snapshot of how prepared the members of this group are.
     * Ideal for your org dashboard / leader view.
     *
     * <p><b>Admin or owner only, since 2026-08-24.</b> This required nothing but a
     * signed-in caller, and {@code buildReadinessSummary} takes no caller
     * argument at all — so it could not have gated even if it wanted to. For a
     * household id it returned the owner's email alongside a live list of who
     * needs assistance: a directory of which homes have someone vulnerable in
     * them, keyed by an id other endpoints hand out.</p>
     *
     * <p>Admin is the right level rather than membership: the only consumers are
     * OrgAdminDashboard and AgencyAdminDashboard, both already admin-gated
     * surfaces, and the payload is a leader view by design.</p>
     */
    @GetMapping("/{groupId}/readiness-summary")
    public ResponseEntity<GroupReadinessSummaryDto> getReadinessSummary(
            @PathVariable("groupId") String groupId
    ) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        if (!GroupRole.fromGroup(lookup(groupId), caller).isAtLeastAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Group admin or owner role required");
        }
        GroupReadinessSummaryDto dto = groupReadinessService.buildReadinessSummary(groupId);
        return ResponseEntity.ok(dto);
    }

    /** 404 rather than a leaked "that id exists but you can't have it". */
    private io.sitprep.sitprepapi.domain.Group lookup(String groupId) {
        try {
            return groupService.getGroupByPublicId(groupId);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found");
        }
    }
}