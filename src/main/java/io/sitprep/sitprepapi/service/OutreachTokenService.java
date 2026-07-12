package io.sitprep.sitprepapi.service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.sitprep.sitprepapi.exception.InvalidTokenException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Mints and verifies the stateless one-click opt-out tokens embedded in ghost
 * tenant outreach emails (Phase 3). Recipients aren't registered users and can't
 * log in, so the link itself must carry everything needed to authorise the
 * opt-out — with a signature that proves WE issued it.
 *
 * <p><b>Mechanism.</b> A signed JWT (Auth0 java-jwt, already on the classpath)
 * using <b>HMAC-SHA256 (HS256)</b> over a backend secret. The payload carries the
 * {@code groupId} (subject) and the official contact email (claim); the HMAC
 * signature makes the token tamper-evident. Verification is delegated to the
 * library ({@link JWTVerifier}), which does a constant-time signature check plus
 * issuer/expiry validation — we never hand-roll crypto or string-compare
 * signatures ourselves.</p>
 *
 * <p><b>Stateless by design.</b> No database row backs a token: the payload +
 * signature ARE the state. Nothing to persist, migrate, or clean up.</p>
 *
 * <p><b>Secret.</b> Injected via {@code sitprep.security.outreach-secret}. The
 * baked-in {@code default-dev-secret} is for local/test only — production MUST
 * override it (Heroku config var {@code SITPREP_SECURITY_OUTREACH_SECRET}), or
 * anyone could forge a valid opt-out link. (Low blast radius — the only action a
 * forged token authorises is unsubscribing a civic page from outreach, which is
 * safe and reversible — but still worth a real secret.)</p>
 */
@Service
public class OutreachTokenService {

    private static final String ISSUER = "sitprep-outreach";
    private static final String CLAIM_EMAIL = "eml";

    /**
     * Opt-out links are intentionally long-lived: a recipient may click a notice
     * weeks after it lands, and an expired opt-out link that silently fails would
     * be user-hostile. The expiry only bounds the signed-token lifetime; it isn't
     * a security control (opt-out is a safe, reversible action).
     */
    private static final Duration TOKEN_TTL = Duration.ofDays(365);

    private final Algorithm algorithm;
    private final JWTVerifier verifier;
    private final String optOutBaseUrl;

    public OutreachTokenService(
            @Value("${sitprep.security.outreach-secret:default-dev-secret}") String secret,
            @Value("${sitprep.outreach.opt-out-base-url:http://localhost:8080/api/public/outreach/opt-out}")
            String optOutBaseUrl) {
        this.algorithm = Algorithm.HMAC256(secret);
        this.verifier = JWT.require(algorithm).withIssuer(ISSUER).build();
        this.optOutBaseUrl = optOutBaseUrl;
    }

    /**
     * Mint a signed, URL-safe opt-out token binding {@code groupId} + the
     * official contact email. JWTs are base64url-encoded, so the result is safe
     * to drop straight into a query string.
     */
    public String generateToken(String groupId, String email) {
        Instant now = Instant.now();
        return JWT.create()
                .withIssuer(ISSUER)
                .withSubject(groupId)
                .withClaim(CLAIM_EMAIL, email == null ? "" : email)
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(now.plus(TOKEN_TTL)))
                .sign(algorithm);
    }

    /**
     * Verify a token and return the {@code groupId} it was minted for.
     *
     * @throws InvalidTokenException if the signature, issuer, or expiry doesn't
     *         check out, or the token is malformed / carries no group reference.
     */
    public String validateAndExtractGroupId(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("Missing opt-out token");
        }
        try {
            DecodedJWT decoded = verifier.verify(token);
            String groupId = decoded.getSubject();
            if (groupId == null || groupId.isBlank()) {
                throw new InvalidTokenException("Token carries no group reference");
            }
            return groupId;
        } catch (JWTVerificationException e) {
            // Covers bad signature (tamper), wrong issuer, expiry, and malformed
            // input. Deliberately opaque — don't reveal which check failed.
            throw new InvalidTokenException("Invalid or tampered opt-out token", e);
        }
    }

    /** Full one-click opt-out URL for an outreach email: {@code <base>?token=<jwt>}. */
    public String buildOptOutLink(String groupId, String email) {
        return optOutBaseUrl + "?token=" + generateToken(groupId, email);
    }
}
