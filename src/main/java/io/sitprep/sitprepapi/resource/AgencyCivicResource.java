package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.ApiMeta;
import io.sitprep.sitprepapi.dto.ApiResponse;
import io.sitprep.sitprepapi.dto.CivicQueueDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.service.AgencyAuthorizationService;
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
 * Civic epic Slice 1 — an authorized agency's civic-report pending queue
 * (READ-ONLY). The agency sees the resident infrastructure reports tagged to
 * it, filterable by {@code CivicStatus}. No claim / merge / dedupe here — those
 * are Slices 2–4.
 *
 * <pre>
 *   GET /api/agencies/{groupId}/civic-reports              all statuses
 *   GET /api/agencies/{groupId}/civic-reports?status=reported
 * </pre>
 *
 * <p>Gated via {@link AgencyAuthorizationService#requireAgencyAdmin} — the
 * caller must be an admin/owner of an {@code agencyAuthorized} group (decision
 * 6). BE is the enforcement boundary; the FE mirrors this gate for UX only.</p>
 */
@RestController
public class AgencyCivicResource {

    private final PostService posts;
    private final GroupRepo groupRepo;
    private final AgencyAuthorizationService agencyAuth;

    public AgencyCivicResource(PostService posts, GroupRepo groupRepo,
                               AgencyAuthorizationService agencyAuth) {
        this.posts = posts;
        this.groupRepo = groupRepo;
        this.agencyAuth = agencyAuth;
    }

    @GetMapping("/api/agencies/{groupId}/civic-reports")
    public ResponseEntity<ApiResponse<CivicQueueDto>> civicReports(
            @PathVariable String groupId,
            @RequestParam(value = "status", required = false) String status) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        Group agency = groupRepo.findByGroupId(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agency group not found"));
        agencyAuth.requireAgencyAdmin(agency, caller);
        return ResponseEntity.ok(ApiResponse.ok(
                posts.listCivicReportsForAgency(groupId, status), ApiMeta.now()));
    }
}
