package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.constant.PlatformPermission;
import io.sitprep.sitprepapi.dto.PublisherPublishAuditDto;
import io.sitprep.sitprepapi.dto.ReviewPublisherPublishAuditRequest;
import io.sitprep.sitprepapi.service.PlatformAccessService;
import io.sitprep.sitprepapi.service.PublisherPublishAuditService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class PublisherPublishAuditResource {

    private final PublisherPublishAuditService service;
    private final PlatformAccessService platformAccessService;

    public PublisherPublishAuditResource(PublisherPublishAuditService service,
                                         PlatformAccessService platformAccessService) {
        this.service = service;
        this.platformAccessService = platformAccessService;
    }

    @GetMapping("/api/admin/publisher-publish-reviews")
    public ResponseEntity<List<PublisherPublishAuditDto>> list(
            @RequestParam(value = "status", required = false, defaultValue = "PENDING") String status,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = platformAccessService.resolveForRequest(AuthUtils.getCurrentUserEmail(), token);
        access.require(PlatformPermission.MODERATE_REPORTS);
        return ResponseEntity.ok(service.listReviews(status));
    }

    @PatchMapping("/api/admin/publisher-publish-reviews/{id}")
    public ResponseEntity<PublisherPublishAuditDto> review(
            @PathVariable Long id,
            @RequestBody ReviewPublisherPublishAuditRequest req,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = platformAccessService.resolveForRequest(AuthUtils.getCurrentUserEmail(), token);
        access.require(PlatformPermission.MODERATE_REPORTS);
        return ResponseEntity.ok(service.review(id, req, access.auditActorEmail()));
    }
}
