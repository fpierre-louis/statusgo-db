package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.constant.PlatformPermission;
import io.sitprep.sitprepapi.dto.CommunityReportDto;
import io.sitprep.sitprepapi.dto.CreateCommunityReportRequest;
import io.sitprep.sitprepapi.dto.ReviewCommunityReportRequest;
import io.sitprep.sitprepapi.service.PlatformAccessService;
import io.sitprep.sitprepapi.service.CommunityReportService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class CommunityReportResource {

    private final CommunityReportService service;
    private final PlatformAccessService platformAccessService;

    public CommunityReportResource(CommunityReportService service,
                                   PlatformAccessService platformAccessService) {
        this.service = service;
        this.platformAccessService = platformAccessService;
    }

    @PostMapping("/api/community/reports")
    public ResponseEntity<CommunityReportDto> create(@RequestBody CreateCommunityReportRequest req) {
        String reporter = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req, reporter));
    }

    @GetMapping("/api/admin/community-reports")
    public ResponseEntity<List<CommunityReportDto>> list(
            @RequestParam(value = "status", required = false, defaultValue = "PENDING") String status,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = platformAccessService.resolveForRequest(AuthUtils.getCurrentUserEmail(), token);
        access.require(PlatformPermission.MODERATE_REPORTS);
        return ResponseEntity.ok(service.listReports(status));
    }

    @PatchMapping("/api/admin/community-reports/{id}")
    public ResponseEntity<CommunityReportDto> review(
            @PathVariable Long id,
            @RequestBody ReviewCommunityReportRequest req,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = platformAccessService.resolveForRequest(AuthUtils.getCurrentUserEmail(), token);
        access.require(PlatformPermission.MODERATE_REPORTS);
        return ResponseEntity.ok(service.review(id, req, access.auditActorEmail()));
    }
}
