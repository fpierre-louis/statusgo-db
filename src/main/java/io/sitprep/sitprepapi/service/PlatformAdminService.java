package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.PlatformPermission;
import io.sitprep.sitprepapi.constant.PlatformRole;
import io.sitprep.sitprepapi.domain.PlatformAdmin;
import io.sitprep.sitprepapi.dto.PlatformAdminDto;
import io.sitprep.sitprepapi.repo.PlatformAdminRepo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Service
public class PlatformAdminService {

    private static final EnumSet<PlatformPermission> SENSITIVE_GRANTS = EnumSet.of(
            PlatformPermission.MANAGE_ADMINS,
            PlatformPermission.VIEW_PII);

    private final PlatformAdminRepo repo;
    private final AdminAuditLogService adminAuditLogService;

    public PlatformAdminService(PlatformAdminRepo repo,
                                AdminAuditLogService adminAuditLogService) {
        this.repo = repo;
        this.adminAuditLogService = adminAuditLogService;
    }

    @Transactional(readOnly = true)
    public List<PlatformAdminDto> list() {
        return repo.findAllByActiveTrueOrderByEmailAsc().stream()
                .map(PlatformAdminDto::from)
                .toList();
    }

    @Transactional
    public PlatformAdminDto upsert(String rawEmail,
                                   PlatformRole requestedRole,
                                   Set<PlatformPermission> requestedGrants,
                                   PlatformAccessService.PlatformAccess actor) {
        String email = requireEmail(rawEmail);
        PlatformRole role = requestedRole == null ? PlatformRole.NONE : requestedRole;
        EnumSet<PlatformPermission> grants = normalizeGrants(requestedGrants);
        PlatformAdmin admin = repo.findByEmailIgnoreCase(email).orElseGet(PlatformAdmin::new);
        boolean creating = admin.getId() == null;

        enforceSensitiveGuard(admin, role, grants, actor);
        enforceLastSuperAdminGuard(admin, role);

        Instant now = Instant.now();
        admin.setEmail(email);
        admin.setRole(role);
        admin.setExtraGrants(grants);
        admin.setActive(true);
        admin.setGrantedBy(actor.auditActorEmail());
        if (admin.getGrantedAt() == null) admin.setGrantedAt(now);
        admin.setUpdatedAt(now);

        PlatformAdmin saved = repo.save(admin);
        adminAuditLogService.record(
                actor.auditActorEmail(),
                creating ? "PLATFORM_ADMIN_GRANTED" : "PLATFORM_ADMIN_UPDATED",
                "platform_admin",
                email,
                "role=" + role.name() + " extraGrants=" + grants);
        return PlatformAdminDto.from(saved);
    }

    @Transactional
    public void revoke(String rawEmail, PlatformAccessService.PlatformAccess actor) {
        String email = requireEmail(rawEmail);
        PlatformAdmin admin = repo.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Platform admin not found"));

        enforceSensitiveGuard(admin, PlatformRole.NONE, Set.of(), actor);
        if (admin.isActive() && admin.getRole() == PlatformRole.SUPER_ADMIN
                && repo.countByActiveTrueAndRole(PlatformRole.SUPER_ADMIN) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot revoke the last Super Admin");
        }

        admin.setRole(PlatformRole.NONE);
        admin.setExtraGrants(EnumSet.noneOf(PlatformPermission.class));
        admin.setActive(false);
        admin.setUpdatedAt(Instant.now());
        repo.save(admin);

        adminAuditLogService.record(
                actor.auditActorEmail(),
                "PLATFORM_ADMIN_REVOKED",
                "platform_admin",
                email,
                "revoked all platform access");
    }

    private void enforceSensitiveGuard(PlatformAdmin existing,
                                       PlatformRole requestedRole,
                                       Set<PlatformPermission> requestedGrants,
                                       PlatformAccessService.PlatformAccess actor) {
        if (actor.role() == PlatformRole.SUPER_ADMIN) return;
        if (requestedRole == PlatformRole.SUPER_ADMIN || containsSensitive(requestedGrants)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a Super Admin can grant Super Admin, Manage Admins, or View PII");
        }
        if (existing.getId() != null
                && (existing.getRole() == PlatformRole.SUPER_ADMIN || containsSensitive(existing.getExtraGrants()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a Super Admin can change sensitive platform access");
        }
    }

    private void enforceLastSuperAdminGuard(PlatformAdmin existing, PlatformRole requestedRole) {
        if (existing.getId() == null || !existing.isActive() || existing.getRole() != PlatformRole.SUPER_ADMIN) return;
        if (requestedRole == PlatformRole.SUPER_ADMIN) return;
        if (repo.countByActiveTrueAndRole(PlatformRole.SUPER_ADMIN) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot downgrade the last Super Admin");
        }
    }

    private static boolean containsSensitive(Set<PlatformPermission> permissions) {
        return permissions != null && permissions.stream().anyMatch(SENSITIVE_GRANTS::contains);
    }

    private static EnumSet<PlatformPermission> normalizeGrants(Set<PlatformPermission> grants) {
        EnumSet<PlatformPermission> normalized = EnumSet.noneOf(PlatformPermission.class);
        if (grants != null) {
            grants.stream()
                    .filter(permission -> permission != null)
                    .forEach(normalized::add);
        }
        return normalized;
    }

    private static String requireEmail(String rawEmail) {
        String email = PlatformAccessService.normalizeEmail(rawEmail);
        if (email == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "email is required");
        }
        if (!email.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "email must be valid");
        }
        return email;
    }
}
