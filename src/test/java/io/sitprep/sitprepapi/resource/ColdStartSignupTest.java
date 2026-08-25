package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.security.FirebaseAuthenticationDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Can a brand-new person still create an account after A9?
 *
 * <p>A9 flips {@code /api/**} from {@code permitAll} to {@code .authenticated()}.
 * The one route where getting that wrong is unrecoverable is
 * {@code POST /api/userinfo/firebase} — account provisioning. If it starts
 * answering 401, nobody can sign up, and the failure is invisible to anyone who
 * already has an account.</p>
 *
 * <p>The reasoning said it would be fine because Firebase auth completes before
 * provisioning runs. Reasoning is what this file replaces.</p>
 *
 * <h2>What is proven, and at which layer</h2>
 *
 * <ul>
 *   <li><b>Over HTTP, through the real filter chain</b> — an anonymous call is
 *       already refused <em>today</em>, under the current {@code permitAll}. That
 *       is the load-bearing result: the route is already effectively
 *       authenticated-only, so {@code .authenticated()} cannot change its answer
 *       for an anonymous caller. It also means the allowlist does not need this
 *       row.</li>
 *   <li><b>At the handler, with an authenticated identity</b> — a brand-new UID
 *       with no prior row provisions successfully. The cold path, not a
 *       re-login.</li>
 * </ul>
 *
 * <h2>What is NOT proven, stated plainly</h2>
 *
 * <p>No real Firebase ID token is minted here — the Admin SDK cannot verify one
 * offline, and {@code spring-security-test} is not on the classpath. So this
 * exercises <em>authorization</em> (what the flip changes) and not
 * <em>authentication</em> (what the flip leaves alone).</p>
 *
 * <p>That gap is covered by a fact rather than a mock: the handler calls
 * {@code AuthUtils.requireAuthenticatedUid()}, which throws 401 without a
 * verified token — and signup works in production today. Signup could not work
 * if the frontend were not already sending a token, so the token is already
 * there, which is the whole question the flip turns on.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ColdStartSignupTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserInfoResource userInfoResource;

    @Autowired
    private UserInfoRepo userInfoRepo;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Populate the context the way {@link io.sitprep.sitprepapi.security.FirebaseAuthFilter}
     * does after verifying a token: email as principal, UID on the details.
     */
    private void authenticateAs(String email, String uid) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                email.toLowerCase(), null, Collections.emptyList());
        auth.setDetails(new FirebaseAuthenticationDetails(new MockHttpServletRequest(), uid, null));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ------------------------------------------------------------------
    // Over HTTP — the real filter chain, the real SecurityConfig
    // ------------------------------------------------------------------

    @Test
    @DisplayName("anonymous signup POST is already refused today, so the A9 flip cannot change it")
    void anonymousProvisioningIsAlreadyRefused() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // No Authorization header — exactly what an unauthenticated caller sends.
        ResponseEntity<String> res = rest.exchange(
                "http://localhost:" + port + "/api/userinfo/firebase",
                HttpMethod.POST,
                new HttpEntity<>("{\"userEmail\":\"nobody@x.com\"}", headers),
                String.class);

        assertThat(res.getStatusCode())
                .as("route must already reject anonymous callers under the current permitAll")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an invalid bearer token is refused the same way")
    void garbageTokenIsAlsoRefused() {
        // The filter logs and proceeds anonymously on a bad token rather than
        // rejecting, so this asserts the HANDLER is what closes the door — which
        // is the property that survives the flip.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("not-a-real-firebase-token");

        ResponseEntity<String> res = rest.exchange(
                "http://localhost:" + port + "/api/userinfo/firebase",
                HttpMethod.POST,
                new HttpEntity<>("{\"userEmail\":\"nobody@x.com\"}", headers),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------
    // At the handler — the cold path, with an authenticated identity
    // ------------------------------------------------------------------

    @Test
    @Transactional
    @DisplayName("a brand-new Firebase identity with no prior row provisions successfully")
    void coldStartSignupCreatesTheRow() {
        String uid = "uid-cold-" + UUID.randomUUID();
        String email = "cold-" + UUID.randomUUID() + "@x.com";

        // The cold path is only cold if nothing is there. Assert it, rather than
        // assuming the random id is unused.
        assertThat(userInfoRepo.findByFirebaseUid(uid)).isEmpty();

        authenticateAs(email, uid);

        UserInfo incoming = new UserInfo();
        incoming.setUserEmail(email);
        incoming.setUserFirstName("Cold");
        incoming.setUserLastName("Start");

        ResponseEntity<UserInfo> res = userInfoResource.createOrUpsertByUid(incoming);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().getFirebaseUid()).isEqualTo(uid);
        assertThat(res.getBody().getUserEmail()).isEqualToIgnoringCase(email);

        Optional<UserInfo> persisted = userInfoRepo.findByFirebaseUid(uid);
        assertThat(persisted).as("the account row must actually exist afterwards").isPresent();
    }

    @Test
    @DisplayName("with no identity in the context the handler is what throws 401")
    void handlerItselfRequiresAnIdentity() {
        SecurityContextHolder.clearContext();

        UserInfo incoming = new UserInfo();
        incoming.setUserEmail("nobody@x.com");

        assertThatThrownBy(() -> userInfoResource.createOrUpsertByUid(incoming))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    @Transactional
    @DisplayName("signing in again is an upsert, not a duplicate account")
    void secondSignInDoesNotCreateASecondRow() {
        String uid = "uid-repeat-" + UUID.randomUUID();
        String email = "repeat-" + UUID.randomUUID() + "@x.com";
        authenticateAs(email, uid);

        UserInfo first = new UserInfo();
        first.setUserEmail(email);
        first.setUserFirstName("First");
        userInfoResource.createOrUpsertByUid(first);

        UserInfo second = new UserInfo();
        second.setUserEmail(email);
        second.setUserFirstName("Second");
        ResponseEntity<UserInfo> res = userInfoResource.createOrUpsertByUid(second);

        assertThat(res.getBody()).isNotNull();
        assertThat(userInfoRepo.findByFirebaseUid(uid)).isPresent();
        assertThat(res.getBody().getUserFirstName()).isEqualTo("Second");
    }
}
