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
     * fetchPostsByGroupId with a single call.
     *
     * <p>Viewer identity is resolved from the verified Firebase token when
     * present; falls back to the {@code viewerEmail} query param while
     * Phase E enforcement on reads is still pending.</p>
     */
    @GetMapping("/{groupId}/member")
    public ResponseEntity<GroupMemberViewDto> getMemberView(
            @PathVariable String groupId,
            @RequestParam(name = "viewerEmail", required = false) String viewerEmail
    ) {
        String viewer = AuthUtils.resolveActor(viewerEmail);
        return groupViewService.buildMemberView(groupId, viewer)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
