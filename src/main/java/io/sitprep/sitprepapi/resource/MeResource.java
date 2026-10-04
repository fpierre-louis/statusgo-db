package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.ApiMeta;
import io.sitprep.sitprepapi.dto.ApiResponse;
import io.sitprep.sitprepapi.dto.MeDto;
import io.sitprep.sitprepapi.dto.MePlansDto;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.ConcealmentSafetyService;
import io.sitprep.sitprepapi.service.MeService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/me")
@CrossOrigin(origins = "http://localhost:3000")
public class MeResource {

    private static final Logger log = LoggerFactory.getLogger(MeResource.class);

    private final MeService meService;
    private final ConcealmentSafetyService concealmentSafetyService;
    private final UserInfoRepo userInfoRepo;

    public MeResource(MeService meService,
                      ConcealmentSafetyService concealmentSafetyService,
                      UserInfoRepo userInfoRepo) {
        this.meService = meService;
        this.concealmentSafetyService = concealmentSafetyService;
        this.userInfoRepo = userInfoRepo;
    }

    /**
     * Should the app's own UI sounds stay off for the caller right now?
     *
     * <p>{@code suppressed: true} while a live concealment-sensitive alert (a
     * lockdown or violent threat) covers the caller's last known position or
     * home. It is the same judgment that sends their pushes silently (P0-B),
     * so the app is never louder than its pushes. It is its own endpoint
     * rather than a field on {@code /me/{uid}} because a lockdown can start
     * while the app is open: the client re-asks on resume and on every alert
     * arrival, which would be a full {@code /me} rebuild each time.
     * Cheap: the alert feed is an in-memory snapshot.
     */
    @GetMapping("/sound-policy")
    public Map<String, Boolean> soundPolicy() {
        String email = AuthUtils.requireAuthenticatedEmail();
        boolean suppressed = userInfoRepo.findByUserEmailIgnoreCase(email)
                .map(concealmentSafetyService::isConcealmentSensitiveFor)
                .orElse(false);
        return Map.of("suppressed", suppressed);
    }

    @GetMapping("/{uid}")
    public ResponseEntity<ApiResponse<MeDto>> getMe(
            @PathVariable String uid,
            @RequestParam(name = "profile", required = false) String profile
    ) {
        ensureSelf(uid);
        // MeService wraps its sub-fetches in safeGet, so repo-level errors
        // degrade to null/empty and don't reach here. Any exception that DOES
        // escape is either JSON serialization of the built DTO, an EAGER
        // @ElementCollection load blowing up on a specific user's row, or a
        // field access we didn't guard. Log it with the uid so the next prod
        // 500 is searchable rather than silent.
        //
        // Wrapped in ApiResponse per P0-5: FE axios interceptor unwraps to the
        // inner MeDto for existing callers, while new consumers can read
        // response.envelope.meta.degradedSections. Per P1-9 (BE-06), the
        // degraded-section list now reflects ACTUAL sub-fetch failures via
        // MeBuildContext, not an empty placeholder.
        //
        // Optional {@code ?profile=<idOrEmail>} query param (audit BE-12 /
        // P2-15) folds a {@link io.sitprep.sitprepapi.dto.PublicProfileDto}
        // into {@code MeDto.profilePreview} so PublicProfilePage doesn't
        // need a second round trip on cold boot. Absent param leaves the
        // field null; existing callers see no shape change.
        try {
            Optional<String> profileLookup = Optional.ofNullable(profile)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty());
            return meService.buildMe(uid, profileLookup)
                    .map(result -> ResponseEntity.ok(ApiResponse.ok(
                            result.me(),
                            new ApiMeta(Instant.now(), "v1", result.degradedSections())
                    )))
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("MeResource.getMe failed for uid={}", uid, e);
            throw e;
        }
    }

    /**
     * Lazy plans payload. {@code me/plans/*} pages call this on demand;
     * the dashboard / nav / status surfaces never do. Keeps the main /me
     * response from shipping five plan arrays nobody renders.
     *
     * <p>Returned in the {@link ApiResponse} envelope so the same
     * degraded-section mechanism applies to plans sub-fetches (an evac /
     * meal-plan / contacts table going sideways degrades that one section
     * instead of 500-ing the whole call).</p>
     */
    @GetMapping("/{uid}/plans")
    public ResponseEntity<ApiResponse<MePlansDto>> getMyPlans(@PathVariable String uid) {
        ensureSelf(uid);
        try {
            return meService.buildMePlans(uid)
                    .map(result -> ResponseEntity.ok(ApiResponse.ok(
                            result.plans(),
                            new ApiMeta(Instant.now(), "v1", result.degradedSections())
                    )))
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("MeResource.getMyPlans failed for uid={}", uid, e);
            throw e;
        }
    }

    /**
     * /me/{uid} is strictly self — a signed-in user can only read their own
     * payload. Compares the path uid to the verified Firebase token's uid.
     */
    private static void ensureSelf(String pathUid) {
        String tokenUid = AuthUtils.requireAuthenticatedUid();
        if (pathUid == null || !pathUid.equals(tokenUid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "/me/{uid} requires the path uid to match the verified token");
        }
    }
}
