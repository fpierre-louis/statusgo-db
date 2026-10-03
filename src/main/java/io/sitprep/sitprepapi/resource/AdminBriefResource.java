package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.constant.PlatformPermission;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.service.DailyBriefService;
import io.sitprep.sitprepapi.service.PlatformAccessService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Preview of the daily brief for any point (EXEC-3C, 2026-10-03).
 *
 * <p>The brief is one SitPrep post whose content is built per viewer on read,
 * so there is no per-area log to review. This answers "what would someone at
 * this point see right now", regardless of {@code briefs.dry-run}, so the card
 * can be checked for any region before it goes live. Console access only.</p>
 */
@RestController
public class AdminBriefResource {

    private final PlatformAccessService platformAccessService;
    private final DailyBriefService briefs;

    public AdminBriefResource(PlatformAccessService platformAccessService, DailyBriefService briefs) {
        this.platformAccessService = platformAccessService;
        this.briefs = briefs;
    }

    @GetMapping("/api/admin/briefs/preview")
    public ResponseEntity<Map<String, Object>> preview(
            @RequestParam("lat") double lat,
            @RequestParam("lng") double lng,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = platformAccessService.resolveForRequest(AuthUtils.getCurrentUserEmail(), token);
        access.require(PlatformPermission.VIEW_CONSOLE);
        PostDto.CommunityExtras.BriefView view = briefs.viewFor(lat, lng);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("live", briefs.live());
        out.put("surfacedAt", briefs.surfacedAtNow());
        out.put("generatedAt", Instant.now());
        out.put("brief", view);   // null = the card would be hidden here (no reading)
        return ResponseEntity.ok(out);
    }
}
