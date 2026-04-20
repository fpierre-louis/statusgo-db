package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.GroupMemberViewDto;
import io.sitprep.sitprepapi.service.GroupViewService;
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
     * Pass the caller's email as `viewerEmail` (optional) so viewerRole is
     * populated; falls back to the X-Impersonate-Email request header set by
     * the frontend http interceptor.
     */
    @GetMapping("/{groupId}/member")
    public ResponseEntity<GroupMemberViewDto> getMemberView(
            @PathVariable String groupId,
            @RequestParam(name = "viewerEmail", required = false) String viewerEmail,
            @RequestHeader(name = "X-Impersonate-Email", required = false) String impersonateHeader
    ) {
        String viewer = viewerEmail != null && !viewerEmail.isBlank()
                ? viewerEmail
                : impersonateHeader;
        return groupViewService.buildMemberView(groupId, viewer)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
