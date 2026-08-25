package io.sitprep.sitprepapi.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A9 — `/api/**` requires a verified token, and this is the list of exceptions.
 *
 * <p>The flip itself is one line. <b>All of its risk is in the allowlist:</b> a
 * route that legitimately serves people without an account, and is not named in
 * SecurityConfig, starts answering 401 the moment it ships — and several of them
 * are the flows a stranger hits first. A shared evacuation plan. An invite link.
 * A Stripe webhook. None of those are exercised by anyone on the team, because
 * everyone on the team is signed in.</p>
 *
 * <p>So this file asserts the matchers directly, over the real filter chain, on
 * a real port. It does not assert that each endpoint <em>works</em> — several
 * legitimately answer 400 or 404 for a nonsense id. It asserts the only thing
 * the flip can break: <b>that an anonymous request is not turned away at the
 * door.</b> Anything other than 401 means the matcher let it through, which is
 * the whole question.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SecurityConfigAllowlistTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private ResponseEntity<String> anonymous(HttpMethod method, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // No Authorization header — the entire point.
        return rest.exchange("http://localhost:" + port + path, method,
                new HttpEntity<>("{}", headers), String.class);
    }

    // ------------------------------------------------------------------
    // Reachable without an account
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0} {1} is reachable anonymously")
    @CsvSource({
            // Recipient activation — someone in an emergency with no account.
            "GET,  /api/plans/activations/does-not-exist",
            "GET,  /api/plans/activations/does-not-exist/map-public",
            "POST, /api/plans/activations/does-not-exist/acks",
            // Invite link, opened before signing in.
            "GET,  /api/groups/does-not-exist/preview",
            // Stripe. Never sends a Firebase token.
            "POST, /api/billing/webhook",
            // Ask reads — anonymous by product decision; the FE renders these
            // pages without ProtectedRoute.
            "GET,  /api/ask/questions",
            "GET,  /api/ask/questions/top",
            "GET,  /api/ask/questions/12345",
            "GET,  /api/ask/tips",
            "GET,  /api/ask/tips/12345",
            "GET,  /api/ask/search?q=water",
            // Guest map browsing.
            "GET,  /api/community/map?minLat=40&minLng=-112&maxLat=41&maxLng=-111&zoom=13",
            // Public marketing routes with public FE pages.
            "GET,  /api/retail/products",
            "GET,  /api/readiness/assessment/questions",
            "POST, /api/readiness/assessment/evaluate",
            "POST, /api/agency/requests",
            // Ghost-tenant opt-out — signed token in the URL is the auth.
            "GET,  /api/public/outreach/opt-out?token=nope",
    })
    void allowlistedRoutesAreNotTurnedAwayAtTheDoor(String method, String path) {
        ResponseEntity<String> res = anonymous(HttpMethod.valueOf(method.trim()), path.trim());
        assertThat(res.getStatusCode())
                .as("%s %s must not 401 — it serves people with no account", method, path)
                .isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------
    // Closed, and each one deliberately
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0} {1} requires a token")
    @CsvSource({
            // The audit's findings. Each of these leaked before A1-A8; the flip
            // is what stops the NEXT one from being reachable at all.
            "GET,  /api/userinfo/email/someone@x.com",
            "GET,  /api/households/hh-1/manual-members",
            "GET,  /api/households/hh-1/accompaniments",
            "GET,  /api/households/hh-1/events",
            "GET,  /api/households/hh-1/map-places",
            "GET,  /api/groups/grp-1",
            "GET,  /api/groups/grp-1/readiness-summary",
            // Rows I recommended AGAINST allowlisting. If one of these turns out
            // to need public access, it fails here first rather than in prod.
            "GET,  /api/geocode/search?q=main+st",
            "GET,  /api/geocode/reverse?lat=40&lng=-111",
            "GET,  /api/alerts/active",
            "POST, /api/alerts/refresh",
            "GET,  /api/config/defaults",
            "GET,  /api/verified-publishers",
            "GET,  /api/alert-mode?lat=40&lng=-111",
            // Per-user Ask surface. This is why the Ask entries are listed
            // per-path instead of as /api/ask/** — a wildcard would open it.
            "GET,  /api/ask/bookmarks",
            // The owner's roll-up of everyone's check-in coordinates. Its
            // sibling POST on the same path IS public; the split is the point.
            "GET,  /api/plans/activations/does-not-exist/acks",
            // Account provisioning already required a token before the flip.
            "POST, /api/userinfo/firebase",
    })
    void nonAllowlistedRoutesRequireAToken(String method, String path) {
        ResponseEntity<String> res = anonymous(HttpMethod.valueOf(method.trim()), path.trim());
        assertThat(res.getStatusCode())
                .as("%s %s must require a verified token", method, path)
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an unauthenticated caller gets 401, not Spring's default 403")
    void unauthenticatedIsUnauthorizedNotForbidden() {
        // AuthUtils.requireAuthenticatedEmail has always answered 401 and the
        // frontend branches on it. Without an explicit entry point Spring answers
        // 403 here, which would give clients two dialects of "not signed in".
        assertThat(anonymous(HttpMethod.GET, "/api/userinfo/email/x@x.com").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("share links stay public — they are outside /api/ and the flip must not reach them")
    void shareLinksAreUntouched() {
        assertThat(anonymous(HttpMethod.GET, "/share/group/does-not-exist").getStatusCode())
                .isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
