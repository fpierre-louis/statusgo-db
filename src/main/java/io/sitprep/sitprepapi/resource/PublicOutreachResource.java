package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.exception.InvalidTokenException;
import io.sitprep.sitprepapi.service.GhostTenantService;
import io.sitprep.sitprepapi.service.OutreachTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public (unauthenticated) ghost-tenant endpoints (Phase 3). Lives under
 * {@code /api/public/**}, which {@code SecurityConfig} pins to {@code permitAll}
 * ahead of the general {@code /api/**} rule — a ghost tenant clicking an opt-out
 * link is not a registered user and carries no Firebase token, so this path must
 * stay reachable anonymously even if the rest of {@code /api/**} is later
 * tightened to {@code .authenticated()}.
 *
 * <p>Authorisation here comes entirely from the signed token, not the session:
 * the HMAC signature is proof the link was issued by us. See
 * {@link OutreachTokenService}.</p>
 */
@RestController
@RequestMapping("/api/public/outreach")
public class PublicOutreachResource {

    private static final Logger log = LoggerFactory.getLogger(PublicOutreachResource.class);

    private final OutreachTokenService tokenService;
    private final GhostTenantService ghostTenantService;

    public PublicOutreachResource(OutreachTokenService tokenService,
                                  GhostTenantService ghostTenantService) {
        this.tokenService = tokenService;
        this.ghostTenantService = ghostTenantService;
    }

    /**
     * One-click opt-out. Validates the signed token, then permanently opts the
     * referenced group out of claim outreach.
     *
     * <ul>
     *   <li>200 — opted out (idempotent; a second click is still 200).</li>
     *   <li>400 — token missing, malformed, expired, or tampered.</li>
     *   <li>404 — token valid but the group no longer exists.</li>
     * </ul>
     */
    @GetMapping(value = "/opt-out", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> optOut(@RequestParam(value = "token", required = false) String token) {
        final String groupId;
        try {
            groupId = tokenService.validateAndExtractGroupId(token);
        } catch (InvalidTokenException e) {
            log.info("[ghost-outreach] rejected opt-out click with an invalid/tampered token");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(page("Invalid link",
                            "This opt-out link is invalid or has expired. No changes were made."));
        }

        try {
            ghostTenantService.optOutGroup(groupId);
        } catch (IllegalArgumentException notFound) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(page("Not found",
                            "We couldn't find that page — it may already have been removed."));
        }

        return ResponseEntity.ok(page("Unsubscribed",
                "You have been successfully unsubscribed. You will not receive further "
                        + "claim notices for this page."));
    }

    /**
     * Minimal self-contained success/error page. Every interpolated value is a
     * server-side constant (never the token or any request input), so there's no
     * reflected-XSS surface.
     */
    private static String page(String title, String message) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + title + " — SitPrep</title></head>"
                + "<body style=\"font-family:system-ui,-apple-system,sans-serif;"
                + "max-width:32rem;margin:4rem auto;padding:0 1.25rem;text-align:center;color:#1f2933\">"
                + "<h1 style=\"font-size:1.375rem\">" + title + "</h1>"
                + "<p style=\"font-size:1rem;line-height:1.5\">" + message + "</p>"
                + "</body></html>";
    }
}
