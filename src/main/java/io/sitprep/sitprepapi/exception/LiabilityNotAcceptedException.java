package io.sitprep.sitprepapi.exception;

/**
 * Thrown by the state-machine guard when a liability-gated work order is asked
 * to move into an operational/terminal state (IN_PROGRESS, VERIFICATION_PENDING,
 * CLOSED, DONE) without a captured release — neither {@code releaseSigned} nor a
 * {@code releaseExceptionReason}. Mapped to HTTP 409 Conflict by
 * {@link GlobalExceptionHandler}.
 *
 * <p>This is the application-layer twin of the {@code ck_task_liability_gate}
 * database CHECK constraint (V43): the app returns a clean, client-facing 409;
 * the DB constraint is the unbypassable backstop if any code path skips this
 * guard.</p>
 */
public class LiabilityNotAcceptedException extends RuntimeException {
    public LiabilityNotAcceptedException(String message) {
        super(message);
    }
}
