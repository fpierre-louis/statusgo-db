// src/main/java/io/sitprep/sitprepapi/exception/GlobalExceptionHandler.java
package io.sitprep.sitprepapi.exception;

import io.sitprep.sitprepapi.dto.ApiError;
import io.sitprep.sitprepapi.dto.ApiMeta;
import io.sitprep.sitprepapi.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Build an error response body that satisfies BOTH the canonical
     * envelope shape ({@code {data, error, meta}}) AND the legacy flat
     * shape ({@code {error, message, path}}) that pre-envelope FE
     * callers still read via {@code err.response.data.message}.
     *
     * <p>Review #5 migrated the 3 catch-all handlers (BadRequest /
     * ResponseStatus / Uncaught) from raw maps to this dual shape so
     * the doctrine is consistent end-to-end while in-flight FE code
     * keeps working. The duplicate {@code message} + {@code path} keys
     * are extras the envelope detector in {@code http.js} ignores
     * (it requires {@code data + error + meta} all present; surplus
     * keys are fine). Drop the legacy shim once every FE error
     * consumer reads from {@code response.envelope.error.message}.</p>
     */
    private static Map<String, Object> buildErrorBody(
            String code, String message, HttpServletRequest req) {
        Map<String, Object> body = new HashMap<>();
        // Canonical envelope keys — required for the FE interceptor
        // to recognize this as ApiResponse.error and stash it onto
        // response.envelope.
        body.put("data", null);
        body.put("error", new ApiError(code, message));
        body.put("meta", ApiMeta.now());
        // Legacy compat keys — pre-envelope FE callers still expect
        // err.response.data.message. Safe to remove post-launch.
        body.put("message", message);
        body.put("path", req.getRequestURI());
        return body;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex, HttpServletRequest req) {
        log.warn("Bad request on {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(buildErrorBody("BAD_REQUEST", safeMessage(ex), req));
    }

    /**
     * Honor the status code carried by {@link ResponseStatusException} —
     * this is the canonical way controllers throw HTTP errors (401, 403,
     * 404, etc.). Without this, the catch-all {@link #handleAllUncaughtException}
     * below was wrapping every {@code ResponseStatusException} as a 500,
     * masking real auth + not-found responses as server errors.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex, HttpServletRequest req) {
        HttpStatusCode status = ex.getStatusCode();
        // 4xx is expected client error; log at WARN. 5xx (rare via this path)
        // gets ERROR with stack trace.
        if (status.is5xxServerError()) {
            log.error("ResponseStatus exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        } else {
            log.warn("Client error {} on {} {}: {}", status.value(),
                    req.getMethod(), req.getRequestURI(), ex.getReason());
        }

        String code = "HTTP_" + status.value();
        String message = ex.getReason() != null ? ex.getReason() : ex.getClass().getSimpleName();
        return ResponseEntity.status(status).body(buildErrorBody(code, message, req));
    }

    /**
     * Optimistic-locking conflict — audit P1-6. Entities annotated with
     * {@code @Version} (Group, UserInfo, PlanActivation, HouseholdRitual)
     * throw this when a flush sees a row whose version moved underneath
     * the in-memory copy. The right answer for the client is "reload and
     * retry," so we surface a 409 with a stable {@code STALE_WRITE} code
     * the FE can switch on rather than the default 500 wrap-up.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<?>> handleStaleWrite(
            OptimisticLockingFailureException ex, HttpServletRequest req) {
        log.warn("Stale write on {} {}: {}",
                req.getMethod(), req.getRequestURI(), safeMessage(ex));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiResponse.error(
                        "STALE_WRITE",
                        "Resource was modified by another request. Reload and retry."));
    }

    /**
     * Metered-monetization block — a group hit its monthly work-order
     * allowance. Surfaced as 402 Payment Required with a stable
     * {@code QUOTA_EXCEEDED} code so the FE can route straight to the
     * upgrade / Stripe Checkout flow rather than treating it as a generic
     * error. See WorkOrderQuotaService + DOCS_GROWTH_MONETIZATION.md.
     */
    @ExceptionHandler(io.sitprep.sitprepapi.exception.QuotaExceededException.class)
    public ResponseEntity<Map<String, Object>> handleQuotaExceeded(
            io.sitprep.sitprepapi.exception.QuotaExceededException ex, HttpServletRequest req) {
        log.warn("Work-order quota exceeded on {} {}: {}",
                req.getMethod(), req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(buildErrorBody("QUOTA_EXCEEDED", ex.getMessage(), req));
    }

    /**
     * Liability gate — a work order that requires a signed waiver was asked to
     * advance without one. 409 Conflict with a stable
     * {@code LIABILITY_NOT_ACCEPTED} code. Mirrors the {@code ck_task_liability_gate}
     * DB constraint at the application layer with a clean client message.
     */
    @ExceptionHandler(io.sitprep.sitprepapi.exception.LiabilityNotAcceptedException.class)
    public ResponseEntity<Map<String, Object>> handleLiabilityNotAccepted(
            io.sitprep.sitprepapi.exception.LiabilityNotAcceptedException ex, HttpServletRequest req) {
        log.warn("Liability gate blocked transition on {} {}: {}",
                req.getMethod(), req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(buildErrorBody("LIABILITY_NOT_ACCEPTED", ex.getMessage(), req));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAllUncaughtException(Exception ex, HttpServletRequest req) {
        // Catch-all for anything that escaped the more-specific handlers above.
        // ResponseStatusException is handled separately so 401/403/404/etc.
        // surface with their real status codes — see handleResponseStatus.
        //
        // NEVER surface ex.getMessage() to the client here — for Hibernate /
        // JDBC / Spring internals the message can leak SQL fragments, stack
        // frames, schema names, and table/column identifiers. Server-side
        // logger.error retains the full stack for diagnosis; the response
        // body carries only a static, generic string.
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildErrorBody("INTERNAL_ERROR", "An unexpected error occurred", req));
    }

    private String safeMessage(Exception ex) {
        if (ex == null) return "Unknown error";
        String msg = ex.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : ex.getClass().getSimpleName();
    }
}