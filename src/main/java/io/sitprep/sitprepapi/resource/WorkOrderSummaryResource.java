package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Post.PostStatus;
import io.sitprep.sitprepapi.dto.ApiMeta;
import io.sitprep.sitprepapi.dto.ApiResponse;
import io.sitprep.sitprepapi.dto.ManagedWorkOrdersDto;
import io.sitprep.sitprepapi.dto.WorkOrderSummaryDto;
import io.sitprep.sitprepapi.service.PostService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Work-order reads the group page epic moved onto the server (lane B6):
 * <ul>
 *   <li>{@code GET /api/me/work-orders?scope=managed} (audit TK-09): one
 *       triaged, paged board across every group the caller owns or admins,
 *       replacing the dashboard's per-group fan-out.</li>
 *   <li>{@code GET /api/groups/{groupId}/work-order-summary} (audit TK-11):
 *       a group's task counts, for the admin dashboard's tiles.</li>
 * </ul>
 * Same {@code ApiResponse} envelope as the group board.
 */
@RestController
public class WorkOrderSummaryResource {

    private final PostService posts;

    public WorkOrderSummaryResource(PostService posts) {
        this.posts = posts;
    }

    @GetMapping("/api/me/work-orders")
    public ResponseEntity<ApiResponse<ManagedWorkOrdersDto>> myWorkOrders(
            @RequestParam(value = "scope", required = false, defaultValue = "managed") String scope,
            @RequestParam(value = "status", required = false) PostStatus status,
            @RequestParam(value = "offset", required = false) Integer offset,
            @RequestParam(value = "limit", required = false) Integer limit) {
        String me = AuthUtils.requireAuthenticatedEmail();
        if (!"managed".equalsIgnoreCase(scope == null ? "" : scope.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scope must be managed");
        }
        return ResponseEntity.ok(ApiResponse.ok(
                posts.listManagedWorkOrders(me, status, offset, limit), ApiMeta.now()));
    }

    @GetMapping("/api/groups/{groupId}/work-order-summary")
    public ResponseEntity<ApiResponse<WorkOrderSummaryDto>> summary(@PathVariable String groupId) {
        String me = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(ApiResponse.ok(posts.workOrderSummary(groupId, me), ApiMeta.now()));
    }
}
