package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.constant.PlatformPermission;
import io.sitprep.sitprepapi.dto.AddAgencyRequestNoteRequest;
import io.sitprep.sitprepapi.dto.AgencyRequestDetailDto;
import io.sitprep.sitprepapi.dto.AgencyRequestDto;
import io.sitprep.sitprepapi.dto.AssignAgencyRequestRequest;
import io.sitprep.sitprepapi.dto.AuthorizeAgencyRequestRequest;
import io.sitprep.sitprepapi.dto.BulkAssignAgencyRequestsRequest;
import io.sitprep.sitprepapi.dto.CreateAgencyRequestRequest;
import io.sitprep.sitprepapi.dto.PatchAgencyRequestRequest;
import io.sitprep.sitprepapi.dto.SaveAgencyRequestDraftRequest;
import io.sitprep.sitprepapi.service.AgencyRequestService;
import io.sitprep.sitprepapi.service.PlatformAccessService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AgencyRequestResource {

    private final AgencyRequestService service;
    private final PlatformAccessService platformAccessService;

    public AgencyRequestResource(AgencyRequestService service,
                                 PlatformAccessService platformAccessService) {
        this.service = service;
        this.platformAccessService = platformAccessService;
    }

    @PostMapping("/api/agency/requests")
    public ResponseEntity<AgencyRequestDto> create(@RequestBody CreateAgencyRequestRequest req) {
        return ResponseEntity.ok(service.create(req, AuthUtils.getCurrentUserEmail()));
    }

    @GetMapping("/api/admin/requests")
    public ResponseEntity<List<AgencyRequestDto>> list(
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "mine", required = false) Boolean mine,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = resolve(token);
        access.require(PlatformPermission.REVIEW_AGENCY_REQUESTS);
        return ResponseEntity.ok(service.list(state, mine, access.auditActorEmail()));
    }

    @GetMapping("/api/admin/requests/{id}")
    public ResponseEntity<AgencyRequestDetailDto> detail(
            @PathVariable Long id,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = resolve(token);
        access.require(PlatformPermission.REVIEW_AGENCY_REQUESTS);
        return ResponseEntity.ok(service.detail(id));
    }

    @PostMapping("/api/admin/requests/{id}/claim")
    public ResponseEntity<AgencyRequestDto> claim(
            @PathVariable Long id,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = resolve(token);
        access.require(PlatformPermission.REVIEW_AGENCY_REQUESTS);
        return ResponseEntity.ok(service.claim(id, access.auditActorEmail()));
    }

    @PostMapping("/api/admin/requests/{id}/assign")
    public ResponseEntity<AgencyRequestDto> assign(
            @PathVariable Long id,
            @RequestBody AssignAgencyRequestRequest req,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = resolve(token);
        access.require(PlatformPermission.REVIEW_AGENCY_REQUESTS);
        return ResponseEntity.ok(service.assign(id, req, access.auditActorEmail()));
    }

    @PostMapping("/api/admin/requests/bulk-assign")
    public ResponseEntity<List<AgencyRequestDto>> bulkAssign(
            @RequestBody BulkAssignAgencyRequestsRequest req,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = resolve(token);
        access.require(PlatformPermission.REVIEW_AGENCY_REQUESTS);
        return ResponseEntity.ok(service.bulkAssign(req, access.auditActorEmail()));
    }

    @PostMapping("/api/admin/requests/{id}/notes")
    public ResponseEntity<AgencyRequestDetailDto> addNote(
            @PathVariable Long id,
            @RequestBody AddAgencyRequestNoteRequest req,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = resolve(token);
        access.require(PlatformPermission.REVIEW_AGENCY_REQUESTS);
        return ResponseEntity.ok(service.addNote(id, req, access.auditActorEmail()));
    }

    @PatchMapping("/api/admin/requests/{id}")
    public ResponseEntity<AgencyRequestDto> patchState(
            @PathVariable Long id,
            @RequestBody PatchAgencyRequestRequest req,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = resolve(token);
        access.require(PlatformPermission.REVIEW_AGENCY_REQUESTS);
        return ResponseEntity.ok(service.patchState(id, req, access.auditActorEmail()));
    }

    @PutMapping("/api/admin/requests/{id}/draft")
    public ResponseEntity<AgencyRequestDto> saveDraft(
            @PathVariable Long id,
            @RequestBody SaveAgencyRequestDraftRequest req,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = resolve(token);
        access.require(PlatformPermission.PROVISION_AGENCY);
        return ResponseEntity.ok(service.saveDraft(id, req, access.auditActorEmail()));
    }

    @PostMapping("/api/admin/requests/{id}/authorize")
    public ResponseEntity<AgencyRequestDto> authorize(
            @PathVariable Long id,
            @RequestBody(required = false) AuthorizeAgencyRequestRequest req,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = resolve(token);
        access.require(PlatformPermission.GRANT_AUTHORITY_STAMP);
        AuthorizeAgencyRequestRequest request = req == null
                ? new AuthorizeAgencyRequestRequest(true, null, null, null)
                : req;
        return ResponseEntity.ok(service.authorize(id, request, access.auditActorEmail()));
    }

    @PostMapping("/api/admin/requests/{id}/provision")
    public ResponseEntity<AgencyRequestDto> provision(
            @PathVariable Long id,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = resolve(token);
        access.require(PlatformPermission.GRANT_AUTHORITY_STAMP);
        return ResponseEntity.ok(service.provision(id, access.auditActorEmail()));
    }

    private PlatformAccessService.PlatformAccess resolve(String token) {
        return platformAccessService.resolveForRequest(AuthUtils.getCurrentUserEmail(), token);
    }
}
