// src/main/java/io/sitprep/sitprepapi/util/AuthUtils.java
package io.sitprep.sitprepapi.util;

import io.sitprep.sitprepapi.security.FirebaseAuthenticationDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

public final class AuthUtils {
    private AuthUtils() {}

    public static String getCurrentUserEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        String name = auth.getName();
        if (name == null || "anonymousUser".equalsIgnoreCase(name)) return null;
        return name;
    }

    /**
     * Returns the verified Firebase UID for the current request, or null if
     * no valid token was attached. Use this in preference to email where the
     * call site wants a stable identity (email can change).
     */
    public static String getCurrentFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        if (auth.getDetails() instanceof FirebaseAuthenticationDetails fad) {
            return fad.getFirebaseUid();
        }
        return null;
    }

    /**
     * Returns the verified email from the token when present, else the
     * supplied fallback. Transitional helper: resources accept an actor in
     * the request body/params during the auth rollout; when enforcement
     * flips, drop the fallback and require the token-derived email.
     */
    public static String resolveActor(String fallback) {
        String fromToken = getCurrentUserEmail();
        return fromToken != null ? fromToken : fallback;
    }

    // ---------------------------------------------------------------------
    // Per-endpoint enforcement helpers
    // ---------------------------------------------------------------------
    //
    // Use these when a specific endpoint should require a verified token
    // even though SecurityConfig is still globally `.permitAll()`. Lets us
    // tighten Phase E one resource at a time without flipping the whole
    // matcher set at once. Frontend must already attach
    // `Authorization: Bearer <firebase-id-token>` for these to pass.
    //
    // Throws ResponseStatusException so Spring renders the standard 401 body
    // — no per-controller exception handler needed.
    // ---------------------------------------------------------------------

    /**
     * Asserts that the current request carries a verified Firebase token and
     * returns the email. Throws 401 otherwise.
     *
     * <p>Use in resource methods that should never run anonymously, e.g.:
     * <pre>{@code
     *   @PostMapping("/api/images")
     *   public Result upload(@RequestParam MultipartFile file) {
     *       String actor = AuthUtils.requireAuthenticatedEmail();
     *       ...
     *   }
     * }</pre>
     */
    public static String requireAuthenticatedEmail() {
        String email = getCurrentUserEmail();
        if (email == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Authenticated request required");
        }
        return email;
    }

    /**
     * Same as {@link #requireAuthenticatedEmail()} but returns the Firebase
     * UID. Prefer this where a stable identity matters (email can change).
     */
    public static String requireAuthenticatedUid() {
        String uid = getCurrentFirebaseUid();
        if (uid == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Authenticated request required");
        }
        return uid;
    }

    /**
     * Asserts that the verified-token email matches {@code expected} (case
     * insensitive). Throws 403 if a different verified user is present, 401
     * if no verified user. Use in "edit my own resource" paths to defend
     * against a logged-in attacker submitting another user's id.
     *
     * <p>During the auth rollout call sites that don't yet enforce can use
     * {@link #resolveActor(String)} instead — that prefers the verified
     * email but falls back to the body param.</p>
     */
    public static void requireSelf(String expected) {
        String actual = requireAuthenticatedEmail();
        if (expected == null || !expected.trim().equalsIgnoreCase(actual)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Verified user does not match the requested resource owner");
        }
    }
}
