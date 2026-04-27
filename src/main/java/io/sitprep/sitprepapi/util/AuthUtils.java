// src/main/java/io/sitprep/sitprepapi/util/AuthUtils.java
package io.sitprep.sitprepapi.util;

import io.sitprep.sitprepapi.security.FirebaseAuthenticationDetails;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

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
}
