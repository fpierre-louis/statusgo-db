package io.sitprep.sitprepapi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled driver for ghost-tenant claim outreach (Phase 3). Runs on a
 * daily-ish tick; the actual per-group weekly cadence + lifetime cap + opt-out
 * are enforced inside {@link GhostTenantService#processOutreach()} / the repo
 * query, so running the tick more often than weekly is safe (it just no-ops for
 * groups that aren't yet due). Staggered {@code initialDelay} so it doesn't
 * contend with the other schedulers on the small pool.
 *
 * <p>Privacy: this only ever triggers simulated emails to human-verified
 * official contacts. No scraping, no social-media APIs. Each notice carries a
 * stateless, HMAC-signed one-click opt-out link — assembled in
 * {@link GhostTenantService#processOutreach()} via {@code OutreachTokenService}
 * and served by {@code PublicOutreachResource} — so a recipient can permanently
 * unsubscribe without ever creating an account.</p>
 */
@Component
public class GhostTenantOutreachWorker {

    private static final Logger log = LoggerFactory.getLogger(GhostTenantOutreachWorker.class);

    private final GhostTenantService ghostTenantService;

    public GhostTenantOutreachWorker(GhostTenantService ghostTenantService) {
        this.ghostTenantService = ghostTenantService;
    }

    @Scheduled(
            fixedDelayString = "${ghost.outreach.intervalMs:86400000}",     // daily
            initialDelayString = "${ghost.outreach.initialDelayMs:180000}") // +3 min after boot
    public void run() {
        try {
            ghostTenantService.processOutreach();
        } catch (Exception e) {
            log.error("[ghost-outreach] worker run failed", e);
        }
    }
}
