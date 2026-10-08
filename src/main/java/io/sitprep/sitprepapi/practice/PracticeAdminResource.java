package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.constant.PlatformPermission;
import io.sitprep.sitprepapi.practice.PracticeAvailabilityService.ContentStatus;
import io.sitprep.sitprepapi.service.PlatformAccessService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * Operator controls for Practice content — the kill switch.
 *
 * <ul>
 *   <li>{@code GET    /api/admin/practice/content} — every loaded version, its
 *       publish state, hash and switch state, plus rejected files and why</li>
 *   <li>{@code PUT    /api/admin/practice/content/{key}/disable} {@code {reason}} —
 *       takes the key out of service now (starts AND in-progress runs)</li>
 *   <li>{@code DELETE /api/admin/practice/content/{key}/disable} — back in service</li>
 * </ul>
 *
 * <p>Gated by {@link PlatformPermission#MANAGE_PRACTICE_CONTENT}, which only
 * SUPER_ADMIN holds by default: whoever moderates a reported comment does not
 * thereby get authority over safety-reviewed preparedness content. The break-glass
 * {@code X-Sitprep-Admin-Token} works here as on every admin route, so the
 * switch can be thrown even when no console admin is at hand.</p>
 */
@RestController
@RequestMapping("/api/admin/practice/content")
public class PracticeAdminResource {

    public record DisableRequest(String reason) {}

    public record StatusResponse(boolean practiceEnabled, boolean previewActive, List<ContentStatus> content,
                                 List<PracticeCatalog.Rejected> rejected) {}

    public record ControlResponse(String key, boolean disabled, Instant disabledAt,
                                  String disabledReason, String updatedBy) {}

    private final PracticeAvailabilityService availability;
    private final PlatformAccessService platformAccessService;

    public PracticeAdminResource(PracticeAvailabilityService availability,
                                 PlatformAccessService platformAccessService) {
        this.availability = availability;
        this.platformAccessService = platformAccessService;
    }

    @GetMapping
    public ResponseEntity<StatusResponse> status(
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token) {
        requireContentManager(token);
        return ResponseEntity.ok(new StatusResponse(availability.practiceEnabled(), availability.previewActive(),
                availability.statusList(), availability.rejected()));
    }

    @PutMapping("/{key}/disable")
    public ResponseEntity<ControlResponse> disable(
            @PathVariable String key,
            @RequestBody(required = false) DisableRequest body,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token) {
        var access = requireContentManager(token);
        validateKey(key);
        String reason = body == null || body.reason() == null ? "" : body.reason().trim();
        if (reason.isEmpty() || reason.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason is required (500 characters or fewer)");
        }
        PracticeContentControl c = availability.disable(key, reason, access.auditActorEmail());
        return ResponseEntity.ok(toResponse(c));
    }

    @DeleteMapping("/{key}/disable")
    public ResponseEntity<ControlResponse> enable(
            @PathVariable String key,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token) {
        var access = requireContentManager(token);
        validateKey(key);
        return availability.enable(key, access.auditActorEmail())
                .map(c -> ResponseEntity.ok(toResponse(c)))
                .orElseGet(() -> ResponseEntity.ok(new ControlResponse(key, false, null, null, null)));
    }

    private PlatformAccessService.PlatformAccess requireContentManager(String token) {
        var access = platformAccessService.resolveForRequest(AuthUtils.getCurrentUserEmail(), token);
        access.require(PlatformPermission.MANAGE_PRACTICE_CONTENT);
        return access;
    }

    private static void validateKey(String key) {
        if (key == null || key.length() > PracticeContentValidator.KEY_MAX
                || !PracticeContentValidator.KEY_FORMAT.matcher(key).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed content key");
        }
    }

    private static ControlResponse toResponse(PracticeContentControl c) {
        return new ControlResponse(c.getContentKey(), c.isDisabled(), c.getDisabledAt(),
                c.getDisabledReason(), c.getUpdatedBy());
    }
}
