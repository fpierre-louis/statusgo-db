package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.GroupReadinessSummaryDto;
import io.sitprep.sitprepapi.service.GroupReadinessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupReadinessResource {

    private final GroupReadinessService groupReadinessService;

    /**
     * GET /api/groups/{groupId}/readiness-summary
     *
     * Returns a high-level snapshot of how prepared the members of this group are.
     * Ideal for your org dashboard / leader view.
     */
    @GetMapping("/{groupId}/readiness-summary")
    public ResponseEntity<GroupReadinessSummaryDto> getReadinessSummary(
            @PathVariable("groupId") String groupId
    ) {
        GroupReadinessSummaryDto dto = groupReadinessService.buildReadinessSummary(groupId);
        return ResponseEntity.ok(dto);
    }
}