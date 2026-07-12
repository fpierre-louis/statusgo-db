package io.sitprep.sitprepapi.exception;

/**
 * Thrown when a stateless outreach token fails verification — bad signature,
 * wrong issuer, expired, or structurally malformed. Unchecked so the token
 * service and callers stay clean; {@code PublicOutreachResource} catches it and
 * renders a 400 (never leaking whether the underlying group exists).
 *
 * <p>Deliberately generic in its message: we do NOT echo the offending token or
 * any decoded claim back to the caller.</p>
 */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }

    public InvalidTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
