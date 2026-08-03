package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.constant.PlatformPermission;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.AdminAgencyDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.service.AdminAuditLogService;
import io.sitprep.sitprepapi.service.GroupService;
import io.sitprep.sitprepapi.service.PlatformAccessService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

/**
 * Platform-scoped management of an agency's ADMIN roster and its owner.
 *
 * <p>Lane B1 of {@code docs/audits/2026-07-30-agency-admin-management/FINDINGS.md}.
 * Before this, the platform console could create and authorize an agency and
 * edit its geo, but could not add or remove its admins: the only routes for
 * that are {@code /api/groups/{id}/admins/*}, gated by
 * {@code GroupResource.requireAdminOf}, which resolves the caller's role purely
 * from the group roster and has <b>no platform-tier bypass</b>. A platform
 * super-admin who was not personally on the agency's roster got a 403, and the
 * real repair path for a broken agency roster was raw SQL.</p>
 *
 * <h2>Why a new resource instead of teaching {@code requireAdminOf} about the
 * platform tier</h2>
 * {@code requireAdminOf} guards nine endpoints on {@code GroupResource}
 * (update, delete, check-in request/rollup/ping, member approve/reject/remove,
 * admin add/remove, logo). Widening it would silently grant the platform tier
 * write access to all nine at once. These routes are additive and separately
 * gated, so the blast radius of this change is exactly this file — the audit's
 * recommended option.
 *
 * <h2>Gate</h2>
 * Every route here requires {@link PlatformPermission#GRANT_AUTHORITY_STAMP} —
 * owner decision, 2026-08-03. That is {@code PlatformRole.ADMIN} and above and
 * deliberately <b>excludes {@code CONSULTANT}</b>: a consultant may provision
 * and review agencies, but handing a third party operational control of a
 * government workspace is a higher bar than provisioning one. The same bar
 * applies to owner transfer.
 *
 * <p>Note this is a strictly HIGHER bar than the sibling staff endpoints
 * ({@code AgencyStaffResource.requireStaffManager}, which admits any non-NONE
 * platform role). That asymmetry is intentional: staff is a queue-access
 * relationship, group admin is control of the workspace.</p>
 *
 * <h2>Delegation</h2>
 * All three routes delegate to the existing {@link GroupService} methods rather
 * than touching rosters directly, so the platform path and the group-admin path
 * cannot diverge on semantics. In particular {@code addAdmin} also appends to
 * {@code memberEmails} (an admin must be a member), {@code removeAdmin} refuses
 * to demote the owner, and {@code transferOwner} guarantees the new owner is
 * both admin and member. {@code GroupService}'s own {@code requireAdminOrOwner}
 * / {@code requireOwner} helpers are documented no-ops — authorization has
 * always lived at the resource layer, which is where this class enforces it.
 */
@RestController
public class AdminAgencyRosterResource {

    private final GroupService groupService;
    private final GroupRepo groupRepo;
    private final PlatformAccessService platformAccessService;
    private final AdminAuditLogService adminAuditLogService;

    public AdminAgencyRosterResource(GroupService groupService,
                                     GroupRepo groupRepo,
                                     PlatformAccessService platformAccessService,
                                     AdminAuditLogService adminAuditLogService) {
        this.groupService = groupService;
        this.groupRepo = groupRepo;
        this.platformAccessService = platformAccessService;
        this.adminAuditLogService = adminAuditLogService;
    }

    /**
     * Grant admin on an agency. Idempotent. The target does not have to be a
     * member — or even a registered user — already: {@code GroupService.addAdmin}
     * appends to {@code memberEmails} when absent, which is also what repairs an
     * admin-but-not-member row.
     */
    @PostMapping("/api/admin/agencies/{groupId}/admins/add")
    public ResponseEntity<AdminAgencyDto> addAdmin(
            @PathVariable String groupId,
            @RequestBody EmailBody body,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = requireAuthority(token);
        Group agency = requireAgency(groupId);
        String email = requireEmail(body);

        Group saved = groupService.addAdmin(agency.getGroupId(), email);
        adminAuditLogService.record(
                access.auditActorEmail(),
                "AGENCY_ADMIN_ADDED",
                "group",
                saved.getGroupId(),
                "admin=" + email + "; agency=" + saved.getGroupName());
        return ResponseEntity.ok(AdminAgencyDto.from(saved));
    }

    /**
     * Revoke admin on an agency. Group membership is left intact — this drops
     * the role, not the person. {@code GroupService.removeAdmin} throws when the
     * target is the group owner; that surfaces as 409 rather than a 500 (see
     * {@link #requireNotOwner}).
     */
    @PostMapping("/api/admin/agencies/{groupId}/admins/remove")
    public ResponseEntity<AdminAgencyDto> removeAdmin(
            @PathVariable String groupId,
            @RequestBody EmailBody body,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = requireAuthority(token);
        Group agency = requireAgency(groupId);
        String email = requireEmail(body);
        requireNotOwner(agency, email);

        Group saved = groupService.removeAdmin(agency.getGroupId(), email);
        adminAuditLogService.record(
                access.auditActorEmail(),
                "AGENCY_ADMIN_REMOVED",
                "group",
                saved.getGroupId(),
                "admin=" + email + "; agency=" + saved.getGroupName());
        return ResponseEntity.ok(AdminAgencyDto.from(saved));
    }

    /**
     * Change who owns the agency workspace.
     *
     * <p>Routed through {@code GroupService.transferOwner} — owner decision,
     * 2026-08-03 — <b>not</b> through the {@code POST /api/admin/agencies}
     * upsert, which can also write {@code ownerEmail} but leaves residue: it
     * adds the new owner to {@code adminEmails} without demoting the previous
     * one, never adds anyone to {@code memberEmails}, and re-runs the
     * VerifiedPublisher upsert for the new address without un-verifying the old.
     * {@code transferOwner} sets a single owner and guarantees they are admin
     * and member.</p>
     */
    @PostMapping("/api/admin/agencies/{groupId}/owner")
    public ResponseEntity<AdminAgencyDto> transferOwner(
            @PathVariable String groupId,
            @RequestBody EmailBody body,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = requireAuthority(token);
        Group agency = requireAgency(groupId);
        String email = requireEmail(body);
        String previousOwner = agency.getOwnerEmail();

        Group saved = groupService.transferOwner(agency.getGroupId(), email);
        adminAuditLogService.record(
                access.auditActorEmail(),
                "AGENCY_OWNER_TRANSFERRED",
                "group",
                saved.getGroupId(),
                "from=" + (previousOwner == null ? "(none)" : previousOwner)
                        + "; to=" + email
                        + "; agency=" + saved.getGroupName());
        return ResponseEntity.ok(AdminAgencyDto.from(saved));
    }

    // ------------------------------------------------------------------
    // Guards
    // ------------------------------------------------------------------

    private PlatformAccessService.PlatformAccess requireAuthority(String token) {
        var access = platformAccessService.resolveForRequest(AuthUtils.getCurrentUserEmail(), token);
        access.require(PlatformPermission.GRANT_AUTHORITY_STAMP);
        return access;
    }

    /**
     * The group must exist AND be an authorized agency. Scoping these routes to
     * {@code agencyAuthorized} groups keeps them what their path says they are —
     * agency administration — rather than a general-purpose platform override
     * for every group in the system, including households.
     */
    private Group requireAgency(String groupId) {
        Group group = groupRepo.findByGroupId(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agency group not found"));
        if (!group.isAgencyAuthorized()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not an authorized agency");
        }
        return group;
    }

    /**
     * {@code GroupService.removeAdmin} throws a raw {@link SecurityException}
     * when asked to demote the owner, which would surface as a 500. Catch the
     * case up front and return a 409 the console can render.
     */
    private void requireNotOwner(Group agency, String email) {
        if (agency.getOwnerEmail() != null && agency.getOwnerEmail().equalsIgnoreCase(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This person owns the agency. Transfer ownership before removing their admin role.");
        }
    }

    private static String requireEmail(EmailBody body) {
        String raw = body == null ? null : body.email();
        String email = raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid email is required");
        }
        return email;
    }

    /** Mirrors {@code GroupResource.EmailRequest} — {@code {"email": "..."}}. */
    public record EmailBody(String email) {}
}
