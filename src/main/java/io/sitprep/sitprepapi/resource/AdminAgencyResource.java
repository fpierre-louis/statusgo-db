package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.constant.PlatformPermission;
import io.sitprep.sitprepapi.dto.AdminAgencyDto;
import io.sitprep.sitprepapi.dto.CreateAdminAgencyRequest;
import io.sitprep.sitprepapi.dto.RadiusPreviewDto;
import io.sitprep.sitprepapi.dto.RadiusPreviewRequest;
import io.sitprep.sitprepapi.dto.SaveAgencyGeoRequest;
import io.sitprep.sitprepapi.service.AdminAgencyService;
import io.sitprep.sitprepapi.service.PlatformAccessService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AdminAgencyResource {

    private final AdminAgencyService service;
    private final PlatformAccessService platformAccessService;

    public AdminAgencyResource(AdminAgencyService service,
                               PlatformAccessService platformAccessService) {
        this.service = service;
        this.platformAccessService = platformAccessService;
    }

    @GetMapping("/api/admin/agencies")
    public ResponseEntity<List<AdminAgencyDto>> list(
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = resolve(token);
        access.require(PlatformPermission.PROVISION_AGENCY);
        return ResponseEntity.ok(service.list());
    }

    @PostMapping("/api/admin/agencies/radius-preview")
    public ResponseEntity<RadiusPreviewDto> preview(
            @RequestBody RadiusPreviewRequest req,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = resolve(token);
        access.require(PlatformPermission.PROVISION_AGENCY);
        return ResponseEntity.ok(service.preview(req));
    }

    @PostMapping("/api/admin/agencies")
    public ResponseEntity<AdminAgencyDto> create(
            @RequestBody CreateAdminAgencyRequest req,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = resolve(token);
        access.require(PlatformPermission.PROVISION_AGENCY);
        access.require(PlatformPermission.GRANT_AUTHORITY_STAMP);
        return ResponseEntity.ok(service.create(req, access.auditActorEmail()));
    }

    @PutMapping("/api/admin/agencies/{groupId}/geo")
    public ResponseEntity<AdminAgencyDto> updateGeo(
            @PathVariable String groupId,
            @RequestBody SaveAgencyGeoRequest req,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = resolve(token);
        access.require(PlatformPermission.PROVISION_AGENCY);
        return ResponseEntity.ok(service.updateGeo(groupId, req, access.auditActorEmail()));
    }

    private PlatformAccessService.PlatformAccess resolve(String token) {
        return platformAccessService.resolveForRequest(AuthUtils.getCurrentUserEmail(), token);
    }
}
