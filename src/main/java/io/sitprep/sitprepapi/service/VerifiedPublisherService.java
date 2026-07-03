package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.VerifiedPublisherDto;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.util.GeoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Verified publisher tier — the trust layer above regular users per
 * {@code docs/SPONSORED_AND_ALERT_MODE.md} "The verified-publisher
 * tier". Manual verification only for v1 (no self-serve); a SitPrep
 * admin reviews paperwork (state business registration, government
 * domain email) and flips the flag via the admin endpoint.
 *
 * <p>Two surfaces use this:</p>
 * <ul>
 *   <li>{@link #discoverInRadius} — public discovery, "verified
 *       services in your area". Used by the eventual "verified
 *       service nearby" lane during alert mode.</li>
 *   <li>{@link #setVerified} — admin-only flip of the entity flag +
 *       audit fields. Idempotent; calling true → true is a no-op.</li>
 * </ul>
 *
 * <p><b>Authorized kinds</b> are validated at the service layer so a
 * future free-form admin tool can't accidentally write a typo into
 * the column. Adding a new kind is a one-line change here.</p>
 */
@Service
public class VerifiedPublisherService {

    private static final Logger log = LoggerFactory.getLogger(VerifiedPublisherService.class);

    /**
     * Authorized verified-publisher kinds. Lowercased. New kinds get
     * added here; the column is free-form length 32 so the schema
     * doesn't need to change.
     */
    private static final Set<String> AUTHORIZED_KINDS = Set.of(
            "business",
            "organization",
            "city",
            "county",
            "state",
            "newsroom",
            "utility",
            "red-cross",
            "official-agency",
            "other"
    );

    private final UserInfoRepo userInfoRepo;
    private final AdminAuditLogService adminAuditLogService;

    public VerifiedPublisherService(UserInfoRepo userInfoRepo,
                                    AdminAuditLogService adminAuditLogService) {
        this.userInfoRepo = userInfoRepo;
        this.adminAuditLogService = adminAuditLogService;
    }

    /**
     * Verified publishers within {@code radiusKm} of the query point,
     * sorted by distance ascending. Capped at 50 like other community-
     * scope reads. Publishers without home coords are excluded (no way
     * to compute distance) — admin tooling should populate
     * latitude/longitude when verifying.
     */
    @Transactional(readOnly = true)
    public List<VerifiedPublisherDto> discoverInRadius(double lat, double lng, double radiusKm) {
        List<UserInfo> all = userInfoRepo.findByVerifiedPublisherTrue();
        List<VerifiedPublisherDto> within = new ArrayList<>();
        for (UserInfo u : all) {
            // UserInfo stores lat/lng as String columns (legacy
            // schema). Parse defensively here — bad/empty values just
            // exclude the publisher from the radius result rather than
            // crash the whole call.
            Double pubLat = u.getLatitude();
            Double pubLng = u.getLongitude();
            if (pubLat == null || pubLng == null) continue;
            double d = GeoUtil.haversineKm(lat, lng, pubLat, pubLng);
            if (d > radiusKm) continue;
            within.add(VerifiedPublisherDto.fromEntity(u, roundKm(d)));
        }
        within.sort(Comparator.comparingDouble(d ->
                d.distanceKm() == null ? Double.MAX_VALUE : d.distanceKm()));
        return within.size() > 50 ? within.subList(0, 50) : within;
    }

    /**
     * Single-publisher fetch by email. Returns the same DTO shape as
     * {@link #discoverInRadius} (no distance — that's only set on radius
     * responses). Returns empty when the email doesn't resolve to a
     * UserInfo OR resolves to a non-verified user (we don't expose the
     * verification flag publicly via this endpoint; non-verified users
     * are simply "not found" from the public-facing surface).
     */
    @Transactional(readOnly = true)
    public java.util.Optional<VerifiedPublisherDto> findByEmail(String email) {
        if (email == null || email.isBlank()) return java.util.Optional.empty();
        return userInfoRepo.findByUserEmail(email.trim())
                .filter(UserInfo::isVerifiedPublisher)
                .map(u -> VerifiedPublisherDto.fromEntity(u, null));
    }

    /**
     * Flip the verified flag. {@code verified=true} requires a non-null
     * kind from {@link #AUTHORIZED_KINDS}; {@code verified=false} clears
     * the audit fields so re-verifying later starts fresh.
     */
    @Transactional
    public VerifiedPublisherDto setVerified(String userEmail, boolean verified, String kind, String adminEmail) {
        return setVerified(userEmail, verified, kind, adminEmail, null, null, null, false, null);
    }

    @Transactional
    public VerifiedPublisherDto setVerified(String userEmail,
                                            boolean verified,
                                            String kind,
                                            String adminEmail,
                                            String serviceArea,
                                            String permanentAddress,
                                            String temporaryEventAddress,
                                            boolean emergencyPostingEnabled,
                                            String groupId) {
        UserInfo u = userInfoRepo.findByUserEmailIgnoreCase(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userEmail));
        return applyVerification(
                u,
                userEmail,
                verified,
                kind,
                adminEmail,
                serviceArea,
                permanentAddress,
                temporaryEventAddress,
                emergencyPostingEnabled,
                groupId);
    }

    /**
     * Agency authorization can happen before the named agency contact has
     * created an app account. In that case the agency group is still the
     * source of truth, and the UserInfo publisher stamp is deferred until the
     * user exists. Manual publisher verification should keep using
     * {@link #setVerified} so a missing user remains visible to the operator.
     */
    @Transactional
    public boolean setVerifiedIfUserExists(String userEmail,
                                           boolean verified,
                                           String kind,
                                           String adminEmail,
                                           String serviceArea,
                                           String permanentAddress,
                                           String temporaryEventAddress,
                                           boolean emergencyPostingEnabled,
                                           String groupId) {
        Optional<UserInfo> user = userInfoRepo.findByUserEmailIgnoreCase(userEmail);
        if (user.isEmpty()) {
            if (verified) {
                String k = requireKind(kind);
                adminAuditLogService.record(
                        adminEmail,
                        "DEFERRED_VERIFIED_PUBLISHER",
                        groupId == null || groupId.isBlank() ? "user" : "group",
                        groupId == null || groupId.isBlank() ? normalizeEmail(userEmail) : groupId,
                        "publisher " + normalizeEmail(userEmail)
                                + " pending user account"
                                + kindSuffix(k));
                log.info("VerifiedPublisher: deferred {} -> true (kind={}) by {}; user account not found",
                        userEmail, k, adminEmail);
            }
            return false;
        }
        applyVerification(
                user.get(),
                userEmail,
                verified,
                kind,
                adminEmail,
                serviceArea,
                permanentAddress,
                temporaryEventAddress,
                emergencyPostingEnabled,
                groupId);
        return true;
    }

    private VerifiedPublisherDto applyVerification(UserInfo u,
                                                   String userEmail,
                                                   boolean verified,
                                                   String kind,
                                                   String adminEmail,
                                                   String serviceArea,
                                                   String permanentAddress,
                                                   String temporaryEventAddress,
                                                   boolean emergencyPostingEnabled,
                                                   String groupId) {
        boolean wasVerified = u.isVerifiedPublisher();
        String oldKind = u.getVerifiedPublisherKind();

        if (verified) {
            String k = requireKind(kind);
            u.setVerifiedPublisher(true);
            u.setVerifiedPublisherKind(k);
            u.setVerifiedSince(Instant.now());
            u.setVerifiedBy(adminEmail);
            u.setVerifiedPublisherServiceArea(trim(serviceArea, 400));
            u.setVerifiedPublisherPermanentAddress(trim(permanentAddress, 400));
            u.setVerifiedPublisherTemporaryEventAddress(trim(temporaryEventAddress, 400));
            u.setVerifiedPublisherEmergencyPostingEnabled(emergencyPostingEnabled);
            u.setVerifiedPublisherGroupId(trim(groupId, 80));
            log.info("VerifiedPublisher: {} → true (kind={}) by {}", userEmail, k, adminEmail);
        } else {
            u.setVerifiedPublisher(false);
            u.setVerifiedPublisherKind(null);
            u.setVerifiedSince(null);
            u.setVerifiedBy(null);
            u.setVerifiedPublisherServiceArea(null);
            u.setVerifiedPublisherPermanentAddress(null);
            u.setVerifiedPublisherTemporaryEventAddress(null);
            u.setVerifiedPublisherEmergencyPostingEnabled(false);
            u.setVerifiedPublisherGroupId(null);
            log.info("VerifiedPublisher: {} → false by {}", userEmail, adminEmail);
        }
        userInfoRepo.save(u);
        adminAuditLogService.record(
                adminEmail,
                verified ? "VERIFIED_PUBLISHER" : "REVOKED_PUBLISHER",
                "user",
                u.getId(),
                "publisher " + normalizeEmail(userEmail)
                        + " verified " + wasVerified + kindSuffix(oldKind)
                        + " -> " + verified + kindSuffix(u.getVerifiedPublisherKind()));
        return VerifiedPublisherDto.fromEntity(u, null);
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private static Double roundKm(double km) {
        return Math.round(km * 10.0) / 10.0;
    }

    private static String trim(String raw, int max) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isBlank()) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String requireKind(String kind) {
        String k = (kind == null) ? null : kind.trim().toLowerCase();
        if (k == null || k.isBlank() || !AUTHORIZED_KINDS.contains(k)) {
            throw new IllegalArgumentException(
                    "kind required and must be one of " + AUTHORIZED_KINDS);
        }
        return k;
    }

    private static String normalizeEmail(String raw) {
        String value = trim(raw, 320);
        return value == null ? "unknown" : value.toLowerCase(java.util.Locale.ROOT);
    }

    private static String kindSuffix(String kind) {
        return kind == null || kind.isBlank() ? "" : " (" + kind + ")";
    }
}
