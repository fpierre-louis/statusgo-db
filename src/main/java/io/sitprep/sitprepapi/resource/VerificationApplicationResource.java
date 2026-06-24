package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.constant.PlatformPermission;
import io.sitprep.sitprepapi.dto.AgencyPipelineSummaryDto;
import io.sitprep.sitprepapi.dto.ReviewVerificationApplicationRequest;
import io.sitprep.sitprepapi.dto.SubmitVerificationApplicationRequest;
import io.sitprep.sitprepapi.dto.VerificationApplicationDto;
import io.sitprep.sitprepapi.service.PlatformAccessService;
import io.sitprep.sitprepapi.service.VerificationApplicationService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;

@RestController
public class VerificationApplicationResource {

    private final VerificationApplicationService service;
    private final PlatformAccessService platformAccessService;

    public VerificationApplicationResource(VerificationApplicationService service,
                                           PlatformAccessService platformAccessService) {
        this.service = service;
        this.platformAccessService = platformAccessService;
    }

    @GetMapping("/api/groups/{groupId}/verification-application")
    public ResponseEntity<VerificationApplicationDto> current(@PathVariable String groupId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        VerificationApplicationDto dto = service.currentForGroup(groupId, caller);
        return dto == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(dto);
    }

    @PostMapping("/api/groups/{groupId}/verification-application")
    public ResponseEntity<VerificationApplicationDto> submit(
            @PathVariable String groupId,
            @RequestBody SubmitVerificationApplicationRequest req
    ) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.submit(groupId, caller, req));
    }

    @GetMapping("/api/admin/verification-applications")
    public ResponseEntity<List<VerificationApplicationDto>> adminList(
            @RequestParam(value = "status", required = false) String status,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = platformAccessService.resolveForRequest(AuthUtils.getCurrentUserEmail(), token);
        access.require(PlatformPermission.REVIEW_AGENCY_REQUESTS);
        return ResponseEntity.ok(service.adminList(status));
    }

    // Aggregate pipeline health for the super-admin readiness card
    // (Phase 5 Slice G). Distinct path from the list above (no path var),
    // so it never collides with the {id} review route.
    @GetMapping("/api/admin/verification-applications/summary")
    public ResponseEntity<AgencyPipelineSummaryDto> adminSummary(
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = platformAccessService.resolveForRequest(AuthUtils.getCurrentUserEmail(), token);
        access.require(PlatformPermission.VIEW_METRICS);
        return ResponseEntity.ok(service.pipelineSummary());
    }

    @PatchMapping("/api/admin/verification-applications/{id}")
    public ResponseEntity<VerificationApplicationDto> adminReview(
            @PathVariable Long id,
            @RequestBody ReviewVerificationApplicationRequest req,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = platformAccessService.resolveForRequest(AuthUtils.getCurrentUserEmail(), token);
        access.require(PlatformPermission.REVIEW_AGENCY_REQUESTS);
        if (isApprove(req)) {
            access.require(PlatformPermission.GRANT_AUTHORITY_STAMP);
        }
        return ResponseEntity.ok(service.adminReview(id, req, access.auditActorEmail()));
    }

    private static boolean isApprove(ReviewVerificationApplicationRequest req) {
        if (req == null || req.status() == null) return false;
        String value = req.status().trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return "APPROVED".equals(value);
    }
}
