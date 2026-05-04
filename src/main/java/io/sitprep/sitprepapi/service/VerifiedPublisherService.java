package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.VerifiedPublisherDto;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    /** Mean Earth radius in km — matches PostService / AlertModeService. */
    private static final double EARTH_RADIUS_KM = 6371.0088;

    /**
     * Authorized verified-publisher kinds. Lowercased. New kinds get
     * added here; the column is free-form length 32 so the schema
     * doesn't need to change.
     */
    private static final Set<String> AUTHORIZED_KINDS = Set.of(
            "city", "county", "state", "newsroom", "utility", "red-cross", "other"
    );

    private final UserInfoRepo userInfoRepo;

    public VerifiedPublisherService(UserInfoRepo userInfoRepo) {
        this.userInfoRepo = userInfoRepo;
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
            Double pubLat = parseDoubleOrNull(u.getLatitude());
            Double pubLng = parseDoubleOrNull(u.getLongitude());
            if (pubLat == null || pubLng == null) continue;
            double d = haversineKm(lat, lng, pubLat, pubLng);
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
        UserInfo u = userInfoRepo.findByUserEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userEmail));

        if (verified) {
            String k = (kind == null) ? null : kind.trim().toLowerCase();
            if (k == null || k.isBlank() || !AUTHORIZED_KINDS.contains(k)) {
                throw new IllegalArgumentException(
                        "kind required and must be one of " + AUTHORIZED_KINDS);
            }
            u.setVerifiedPublisher(true);
            u.setVerifiedPublisherKind(k);
            u.setVerifiedSince(Instant.now());
            u.setVerifiedBy(adminEmail);
            log.info("VerifiedPublisher: {} → true (kind={}) by {}", userEmail, k, adminEmail);
        } else {
            u.setVerifiedPublisher(false);
            u.setVerifiedPublisherKind(null);
            u.setVerifiedSince(null);
            u.setVerifiedBy(null);
            log.info("VerifiedPublisher: {} → false by {}", userEmail, adminEmail);
        }
        userInfoRepo.save(u);
        return VerifiedPublisherDto.fromEntity(u, null);
    }

    // -------------------------------------------------------------------
    // Helpers (Haversine kept inline since the codebase's
    // distance-rounding conventions live in PostService — copy is small
    // enough not to justify extracting a util class).
    // -------------------------------------------------------------------

    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                  * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
    }

    private static Double roundKm(double km) {
        return Math.round(km * 10.0) / 10.0;
    }

    /**
     * UserInfo stores lat/lng as String columns (legacy schema). Parse
     * defensively — bad/empty values return null so the caller just
     * excludes the publisher from the result rather than crashing.
     */
    private static Double parseDoubleOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }
}
