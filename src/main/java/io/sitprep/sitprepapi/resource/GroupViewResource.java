package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.GroupMemberViewDto;
import io.sitprep.sitprepapi.service.GroupViewService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/groups")
@CrossOrigin(origins = "http://localhost:3000")
public class GroupViewResource {

    private final GroupViewService groupViewService;

    public GroupViewResource(GroupViewService groupViewService) {
        this.groupViewService = groupViewService;
    }

    /**
     * Consolidated group view for members / viewers. Replaces the typical
     * group-detail page fan-out of fetchGroupById + fetchAllUserInfo-filter +
     * fetchPostsByGroupId with a single call. The backend gates each
     * member's lastKnownLat/Lng on their per-group sharing pref + the
     * group's alert state.
     *
     * <p>Viewer identity comes from the verified Firebase token.</p>
     *
     * <p><b>Access is NOT merely "authenticated".</b> The payload is a full
     * roster (member emails, names, statuses, last-active, and location subject
     * to each member's sharing pref), so the read is gated in
     * {@code GroupViewService.requireCanReadGroup} to the group's own
     * owner/admin/member, plus platform admins and agency staff — the two
     * populations whose shipped consoles are built from this payload. Anyone
     * else gets 403; an unknown group id still gets 404. See that method for
     * the full rationale.</p>
     */
    @GetMapping("/{groupId}/member")
    public ResponseEntity<GroupMemberViewDto> getMemberView(@PathVariable String groupId) {
        String viewer = AuthUtils.requireAuthenticatedEmail();
        return groupViewService.buildMemberView(groupId, viewer)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
