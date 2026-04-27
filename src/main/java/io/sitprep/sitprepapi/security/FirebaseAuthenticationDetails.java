package io.sitprep.sitprepapi.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

/**
 * Carries the verified Firebase UID alongside the usual remote-address /
 * session metadata. Pull via {@code AuthUtils.getCurrentFirebaseUid()} so
 * callers don't have to cast.
 */
public class FirebaseAuthenticationDetails extends WebAuthenticationDetails {

    private final String firebaseUid;

    public FirebaseAuthenticationDetails(HttpServletRequest request, String firebaseUid) {
        super(request);
        this.firebaseUid = firebaseUid;
    }

    public String getFirebaseUid() {
        return firebaseUid;
    }
}
