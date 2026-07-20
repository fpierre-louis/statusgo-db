package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.constant.PlatformRole;
import io.sitprep.sitprepapi.domain.AgencyStaff;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.ApiMeta;
import io.sitprep.sitprepapi.dto.ApiResponse;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.service.AgencyAuthorizationService;
import io.sitprep.sitprepapi.service.AgencyStaffService;
import io.sitprep.sitprepapi.service.PlatformAccessService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * Agency STAFF management — add / remove / list the non-admin employees of an
 * agency-authorized group (docs/lanes/AGENCY_STAFF_PHASE0_DESIGN.md).
 *
 * <p>Authorized caller (D-e): an agency owner/admin of that group
 * ({@link AgencyAuthorizationService#requireAgencyAdmin}) OR a platform admin
 * (a row in {@code platform_admin} — {@code PlatformAccessService.resolve}).
 * Mirrors {@code AgencyCivicResource}: {@code /api/agencies/{groupId}/...},
 * {@code AuthUtils.requireAuthenticatedEmail()} then the gate, {@code ApiResponse}
 * envelope.</p>
 */
@RestController
public class AgencyStaffResource {

    private final GroupRepo groupRepo;
    private final AgencyAuthorizationService agencyAuth;
    private final AgencyStaffService staff;
    private final PlatformAccessService platformAccess;

    public AgencyStaffResource(GroupRepo groupRepo,
                               AgencyAuthorizationService agencyAuth,
                               AgencyStaffService staff,
                               PlatformAccessService platformAccess) {
        this.groupRepo = groupRepo;
        this.agencyAuth = agencyAuth;
        this.staff = staff;
        this.platformAccess = platformAccess;
    }

    /** Wire shape for a staff roster row. */
    public record StaffDto(String email, String addedBy, Instant addedAt) {}

    /** POST body — add staff by email. */
    public record AddStaffRequest(String email) {}

    @GetMapping("/api/agencies/{groupId}/staff")
    public ResponseEntity<ApiResponse<List<StaffDto>>> list(@PathVariable String groupId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        requireStaffManager(groupId, caller);
        List<StaffDto> rows = staff.list(groupId).stream()
                .map(s -> new StaffDto(s.getUserEmail(), s.getAddedBy(), s.getAddedAt()))
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(rows, ApiMeta.now()));
    }

    @PostMapping("/api/agencies/{groupId}/staff")
    public ResponseEntity<ApiResponse<StaffDto>> add(@PathVariable String groupId,
                                                     @RequestBody AddStaffRequest body) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        requireStaffManager(groupId, caller);
        if (body == null || body.email() == null || body.email().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Staff email required");
        }
        AgencyStaff s = staff.add(groupId, body.email(), caller);
        return ResponseEntity.ok(ApiResponse.ok(
                new StaffDto(s.getUserEmail(), s.getAddedBy(), s.getAddedAt()), ApiMeta.now()));
    }

    // Email carried as a query param (not a path segment) so addresses with dots
    // in the TLD don't collide with Spring's path parsing. Idempotent.
    @DeleteMapping("/api/agencies/{groupId}/staff")
    public ResponseEntity<ApiResponse<Void>> remove(@PathVariable String groupId,
                                                     @RequestParam String email) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        requireStaffManager(groupId, caller);
        staff.remove(groupId, email);
        return ResponseEntity.ok(ApiResponse.ok(null, ApiMeta.now()));
    }

    /**
     * Gate: platform admin (any platform_admin row) OR agency owner/admin of the
     * group. A resident/member/follower is rejected. Throws 404 if the group does
     * not exist, 403 if the caller is neither a platform admin nor an agency
     * owner/admin (via {@code requireAgencyAdmin}).
     */
    private void requireStaffManager(String groupId, String caller) {
        Group agency = groupRepo.findByGroupId(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agency group not found"));
        var access = platformAccess.resolve(caller);
        if (access.role() != PlatformRole.NONE) {
            return; // platform admin — allowed to manage any agency's staff (D-e)
        }
        agencyAuth.requireAgencyAdmin(agency, caller);
    }
}
