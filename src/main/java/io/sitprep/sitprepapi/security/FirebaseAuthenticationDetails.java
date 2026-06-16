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
    /** The token's `picture` claim — the SSO provider photo (Google/Apple/Facebook), or null. */
    private final String providerPicture;

    public FirebaseAuthenticationDetails(HttpServletRequest request, String firebaseUid) {
        this(request, firebaseUid, null);
    }

    public FirebaseAuthenticationDetails(HttpServletRequest request, String firebaseUid, String providerPicture) {
        super(request);
        this.firebaseUid = firebaseUid;
        this.providerPicture = providerPicture;
    }

    public String getFirebaseUid() {
        return firebaseUid;
    }

    public String getProviderPicture() {
        return providerPicture;
    }
}
