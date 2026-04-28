package io.sitprep.sitprepapi.resource;

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
    public ResponseEntity<MeDto> getMe(@PathVariable String uid) {
        ensureSelf(uid);
        // MeService wraps its sub-fetches in safeGet, so repo-level errors
        // degrade to null/empty and don't reach here. Any exception that DOES
        // escape is either JSON serialization of the built DTO, an EAGER
        // @ElementCollection load blowing up on a specific user's row, or a
        // field access we didn't guard. Log it with the uid so the next prod
        // 500 is searchable rather than silent.
        try {
            return meService.buildMe(uid)
                    .map(ResponseEntity::ok)
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
     */
    @GetMapping("/{uid}/plans")
    public ResponseEntity<MePlansDto> getMyPlans(@PathVariable String uid) {
        ensureSelf(uid);
        try {
            return meService.buildMePlans(uid)
                    .map(ResponseEntity::ok)
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
