package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.ApiMeta;
import io.sitprep.sitprepapi.dto.ApiResponse;
import io.sitprep.sitprepapi.dto.MeDto;
import io.sitprep.sitprepapi.dto.MePlansDto;
import io.sitprep.sitprepapi.service.MeService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

@RestController
@RequestMapping("/api/me")
@CrossOrigin(origins = "http://localhost:3000")
public class MeResource {

    private static final Logger log = LoggerFactory.getLogger(MeResource.class);

    private final MeService meService;

    public MeResource(MeService meService) {
        this.meService = meService;
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
