package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.constant.PlatformPermission;
import io.sitprep.sitprepapi.dto.AdminBillingAgencyDto;
import io.sitprep.sitprepapi.dto.AdminBillingOperationsDto;
import io.sitprep.sitprepapi.dto.AdminBillingUserDto;
import io.sitprep.sitprepapi.dto.SaveAgencyBillingOverrideRequest;
import io.sitprep.sitprepapi.dto.SaveUserBillingOverrideRequest;
import io.sitprep.sitprepapi.service.AdminBillingService;
import io.sitprep.sitprepapi.service.PlatformAccessService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AdminBillingResource {

    private final AdminBillingService service;
    private final PlatformAccessService platformAccessService;

    public AdminBillingResource(AdminBillingService service,
                                PlatformAccessService platformAccessService) {
        this.service = service;
        this.platformAccessService = platformAccessService;
    }

    @GetMapping("/api/admin/billing/agencies")
    public ResponseEntity<List<AdminBillingAgencyDto>> agencies(
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = resolve(token);
        access.require(PlatformPermission.MANAGE_BILLING);
        return ResponseEntity.ok(service.listAgencies());
    }

    @GetMapping("/api/admin/billing/operations")
    public ResponseEntity<AdminBillingOperationsDto> operations(
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = resolve(token);
        access.require(PlatformPermission.MANAGE_BILLING);
        return ResponseEntity.ok(service.operations());
    }

    @PutMapping("/api/admin/billing/agencies/{groupId}/override")
    public ResponseEntity<AdminBillingAgencyDto> saveAgencyOverride(
            @PathVariable String groupId,
            @RequestBody SaveAgencyBillingOverrideRequest req,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = resolve(token);
        access.require(PlatformPermission.MANAGE_BILLING);
        return ResponseEntity.ok(service.saveAgencyOverride(groupId, req, access.auditActorEmail()));
    }

    @DeleteMapping("/api/admin/billing/agencies/{groupId}/override")
    public ResponseEntity<AdminBillingAgencyDto> clearAgencyOverride(
            @PathVariable String groupId,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = resolve(token);
        access.require(PlatformPermission.MANAGE_BILLING);
        return ResponseEntity.ok(service.clearAgencyOverride(groupId, access.auditActorEmail()));
    }

    @PutMapping("/api/admin/billing/users/{email}/override")
    public ResponseEntity<AdminBillingUserDto> saveUserOverride(
            @PathVariable String email,
            @RequestBody SaveUserBillingOverrideRequest req,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = resolve(token);
        access.require(PlatformPermission.MANAGE_BILLING);
        return ResponseEntity.ok(service.saveUserOverride(email, req, access.auditActorEmail()));
    }

    @DeleteMapping("/api/admin/billing/users/{email}/override")
    public ResponseEntity<AdminBillingUserDto> clearUserOverride(
            @PathVariable String email,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = resolve(token);
        access.require(PlatformPermission.MANAGE_BILLING);
        return ResponseEntity.ok(service.clearUserOverride(email, access.auditActorEmail()));
    }

    private PlatformAccessService.PlatformAccess resolve(String token) {
        return platformAccessService.resolveForRequest(AuthUtils.getCurrentUserEmail(), token);
    }
}
