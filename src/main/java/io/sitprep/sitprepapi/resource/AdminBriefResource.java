package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.constant.PlatformPermission;
import io.sitprep.sitprepapi.service.DailyBriefScheduler;
import io.sitprep.sitprepapi.service.PlatformAccessService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The daily-brief dry run, readable (EXEC-3B review, 2026-10-03).
 *
 * <p>Heroku keeps 1,500 log lines, so each slot's dry-run briefs scrolled out
 * within hours and a day of them could not be reviewed from logs. This returns
 * the scheduler's in-memory review buffer: the briefs it would have posted and
 * why a due slot did not post, newest first. Console access only; the bodies
 * are public weather sentences and nudges, but the cell keys map where users
 * live at 0.1° resolution, which is not for everyone.</p>
 */
@RestController
public class AdminBriefResource {

    private final PlatformAccessService platformAccessService;
    private final DailyBriefScheduler scheduler;

    public AdminBriefResource(PlatformAccessService platformAccessService, DailyBriefScheduler scheduler) {
        this.platformAccessService = platformAccessService;
        this.scheduler = scheduler;
    }

    @GetMapping("/api/admin/briefs/dry-run")
    public ResponseEntity<List<DailyBriefScheduler.ReviewEntry>> dryRun(
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = platformAccessService.resolveForRequest(AuthUtils.getCurrentUserEmail(), token);
        access.require(PlatformPermission.VIEW_CONSOLE);
        return ResponseEntity.ok(scheduler.recentReview());
    }
}
