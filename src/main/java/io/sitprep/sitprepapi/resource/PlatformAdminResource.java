package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.constant.PlatformPermission;
import io.sitprep.sitprepapi.dto.AdminAuditLogDto;
import io.sitprep.sitprepapi.dto.PlatformAdminDto;
import io.sitprep.sitprepapi.dto.SavePlatformAdminRequest;
import io.sitprep.sitprepapi.service.AdminAuditLogService;
import io.sitprep.sitprepapi.service.PlatformAdminService;
import io.sitprep.sitprepapi.service.PlatformAccessService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class PlatformAdminResource {

    private final PlatformAccessService platformAccessService;
    private final PlatformAdminService platformAdminService;
    private final AdminAuditLogService adminAuditLogService;

    public PlatformAdminResource(PlatformAccessService platformAccessService,
                                 PlatformAdminService platformAdminService,
                                 AdminAuditLogService adminAuditLogService) {
        this.platformAccessService = platformAccessService;
        this.platformAdminService = platformAdminService;
        this.adminAuditLogService = adminAuditLogService;
    }

    @GetMapping("/api/admin/me/access")
    public ResponseEntity<PlatformAccessDto> myAccess() {
        String email = AuthUtils.requireAuthenticatedEmail();
        var access = platformAccessService.resolve(email);
        return ResponseEntity.ok(new PlatformAccessDto(
                access.role().name(),
                access.permissionNames()));
    }

    @GetMapping("/api/admin/audit")
    public ResponseEntity<List<AdminAuditLogDto>> audit(
            @RequestParam(value = "actor", required = false) String actor,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "date", required = false) String date,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = resolve(token);
        access.require(PlatformPermission.VIEW_CONSOLE);
        return ResponseEntity.ok(adminAuditLogService.list(
                actor,
                action,
                date,
                access.has(PlatformPermission.MANAGE_ADMINS),
                access.auditActorEmail()));
    }

    @GetMapping("/api/admin/team")
    public ResponseEntity<List<PlatformAdminDto>> team(
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = resolve(token);
        access.require(PlatformPermission.MANAGE_ADMINS);
        return ResponseEntity.ok(platformAdminService.list());
    }

    @PutMapping("/api/admin/team/{email}")
    public ResponseEntity<PlatformAdminDto> saveTeamMember(
            @PathVariable String email,
            @RequestBody SavePlatformAdminRequest req,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = resolve(token);
        access.require(PlatformPermission.MANAGE_ADMINS);
        return ResponseEntity.ok(platformAdminService.upsert(
                email,
                req == null ? null : req.role(),
                req == null ? null : req.extraGrants(),
                access));
    }

    @DeleteMapping("/api/admin/team/{email}")
    public ResponseEntity<Void> revokeTeamMember(
            @PathVariable String email,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = resolve(token);
        access.require(PlatformPermission.MANAGE_ADMINS);
        platformAdminService.revoke(email, access);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    public record PlatformAccessDto(String role, List<String> permissions) {}

    private PlatformAccessService.PlatformAccess resolve(String token) {
        return platformAccessService.resolveForRequest(AuthUtils.getCurrentUserEmail(), token);
    }
}
