package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.ApiMeta;
import io.sitprep.sitprepapi.dto.ApiResponse;
import io.sitprep.sitprepapi.dto.CivicQueueDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.service.AgencyAuthorizationService;
import io.sitprep.sitprepapi.service.CivicAgencyService;
import io.sitprep.sitprepapi.service.PostService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

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
    private final CivicAgencyService civicAgency;

    public AgencyCivicResource(PostService posts, GroupRepo groupRepo,
                               AgencyAuthorizationService agencyAuth,
                               CivicAgencyService civicAgency) {
        this.posts = posts;
        this.groupRepo = groupRepo;
        this.agencyAuth = agencyAuth;
        this.civicAgency = civicAgency;
    }

    @GetMapping("/api/agencies/{groupId}/civic-reports")
    public ResponseEntity<ApiResponse<CivicQueueDto>> civicReports(
            @PathVariable String groupId,
            @RequestParam(value = "status", required = false) String status) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        requireAgencyAdmin(groupId, caller);
        return ResponseEntity.ok(ApiResponse.ok(
                posts.listCivicReportsForAgency(groupId, status), ApiMeta.now()));
    }

    /**
     * Slice 2 — an authorized, tagged agency CLAIMS a civic report to work it.
     * The claim gates the operational actions (schedule/resolve, work-order
     * spawn, merge); acknowledge stays open to any tagged agency. 409 if the
     * report is already claimed (the one-claim partial index + service guard).
     */
    @PostMapping("/api/agencies/{groupId}/civic-reports/{postId}/claim")
    public ResponseEntity<ApiResponse<CivicAgencyService.ClaimResult>> claim(
            @PathVariable String groupId, @PathVariable Long postId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        requireAgencyAdmin(groupId, caller);
        return ResponseEntity.ok(ApiResponse.ok(civicAgency.claim(postId, groupId, caller), ApiMeta.now()));
    }

    /**
     * Slice 2 — the CLAIMING agency releases the report back to unclaimed
     * (decision 4): claimable again by any tagged agency, no auto-reassign.
     */
    @PostMapping("/api/agencies/{groupId}/civic-reports/{postId}/release")
    public ResponseEntity<ApiResponse<CivicAgencyService.ClaimResult>> release(
            @PathVariable String groupId, @PathVariable Long postId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        requireAgencyAdmin(groupId, caller);
        return ResponseEntity.ok(ApiResponse.ok(civicAgency.release(postId, groupId, caller), ApiMeta.now()));
    }

    /**
     * Slice 3 — a CLAIMING agency MERGES {@code duplicateIds} into the canonical
     * {@code postId}. Claim-gated (decision 6) + cross-agency-claim blocked
     * (decision 7) in the service; the agency layer here only checks admin of an
     * agencyAuthorized group.
     */
    @PostMapping("/api/agencies/{groupId}/civic-reports/{postId}/merge")
    public ResponseEntity<ApiResponse<CivicAgencyService.MergeResult>> merge(
            @PathVariable String groupId, @PathVariable Long postId,
            @RequestBody(required = false) MergeRequest body) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        requireAgencyAdmin(groupId, caller);
        List<Long> dupes = body == null ? null : body.duplicateIds();
        return ResponseEntity.ok(ApiResponse.ok(
                civicAgency.merge(postId, dupes, groupId, caller), ApiMeta.now()));
    }

    /**
     * Slice 3 — UNMERGE a duplicate back to a standalone report (decision 9). Same
     * claim-gate as merge; re-pointed work orders stay with the former canonical.
     */
    @PostMapping("/api/agencies/{groupId}/civic-reports/{duplicateId}/unmerge")
    public ResponseEntity<ApiResponse<CivicAgencyService.UnmergeResult>> unmerge(
            @PathVariable String groupId, @PathVariable Long duplicateId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        requireAgencyAdmin(groupId, caller);
        return ResponseEntity.ok(ApiResponse.ok(
                civicAgency.unmerge(duplicateId, groupId, caller), ApiMeta.now()));
    }

    /** Merge request body — the duplicate report ids to fold into the canonical. */
    public record MergeRequest(List<Long> duplicateIds) {}

    private void requireAgencyAdmin(String groupId, String caller) {
        Group agency = groupRepo.findByGroupId(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agency group not found"));
        agencyAuth.requireAgencyAdmin(agency, caller);
    }
}
