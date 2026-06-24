package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.PlatformPermission;
import io.sitprep.sitprepapi.constant.PlatformRole;
import io.sitprep.sitprepapi.domain.PlatformAdmin;
import io.sitprep.sitprepapi.repo.PlatformAdminRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class PlatformAccessService {

    private static final Logger log = LoggerFactory.getLogger(PlatformAccessService.class);

    private final PlatformAdminRepo platformAdminRepo;
    private final String adminToken;

    public PlatformAccessService(PlatformAdminRepo platformAdminRepo,
                                 @Value("${app.admin.token:}") String adminToken) {
        this.platformAdminRepo = platformAdminRepo;
        this.adminToken = adminToken == null ? "" : adminToken.trim();
    }

    @Transactional(readOnly = true)
    public PlatformAccess resolve(String email) {
        String normalized = normalizeEmail(email);
        if (normalized == null) {
            return new PlatformAccess(null, PlatformRole.NONE,
                    EnumSet.noneOf(PlatformPermission.class), false);
        }
        try {
            return platformAdminRepo.findByEmailIgnoreCaseAndActiveTrue(normalized)
                    .map(admin -> accessFromAdmin(normalized, admin))
                    .orElseGet(() -> new PlatformAccess(normalized, PlatformRole.NONE,
                            EnumSet.noneOf(PlatformPermission.class), false));
        } catch (DataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Platform access unavailable", ex);
        }
    }

    @Transactional(readOnly = true)
    public PlatformAccess resolveForRequest(String email, String token) {
        String normalizedEmail = normalizeEmail(email);
        if (validBreakGlass(token)) {
            log.warn("PlatformAccess: break-glass admin token used by authenticatedEmail={}",
                    normalizedEmail == null ? "(none)" : normalizedEmail);
            return new PlatformAccess(normalizedEmail, PlatformRole.SUPER_ADMIN,
                    EnumSet.allOf(PlatformPermission.class), true);
        }
        if (normalizedEmail == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Authenticated request required");
        }
        return resolve(normalizedEmail);
    }

    private PlatformAccess accessFromAdmin(String email, PlatformAdmin admin) {
        PlatformRole role = admin.getRole() == null ? PlatformRole.NONE : admin.getRole();
        EnumSet<PlatformPermission> permissions = EnumSet.noneOf(PlatformPermission.class);
        permissions.addAll(role.defaults());
        if (admin.getExtraGrants() != null) permissions.addAll(admin.getExtraGrants());
        return new PlatformAccess(email, role, permissions, false);
    }

    private boolean validBreakGlass(String token) {
        return !adminToken.isEmpty() && token != null && constantTimeEquals(token, adminToken);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    public static String normalizeEmail(String email) {
        if (email == null) return null;
        String value = email.trim();
        return value.isBlank() ? null : value.toLowerCase(Locale.ROOT);
    }

    public record PlatformAccess(String email,
                                 PlatformRole role,
                                 Set<PlatformPermission> permissions,
                                 boolean breakGlass) {
        public boolean has(PlatformPermission permission) {
            return permission != null && permissions.contains(permission);
        }

        public void require(PlatformPermission permission) {
            if (!has(permission)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Missing " + permission);
            }
        }

        public String auditActorEmail() {
            if (breakGlass) return "admin-token";
            return email == null || email.isBlank() ? "unknown" : email;
        }

        public List<String> permissionNames() {
            List<String> names = new ArrayList<>();
            for (PlatformPermission permission : PlatformPermission.values()) {
                if (permissions.contains(permission)) names.add(permission.name());
            }
            return names;
        }
    }
}
