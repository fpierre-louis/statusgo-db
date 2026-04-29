// src/main/java/io/sitprep/sitprepapi/exception/GlobalExceptionHandler.java
package io.sitprep.sitprepapi.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex, HttpServletRequest req) {
        log.warn("Bad request on {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());

        Map<String, String> errorResponse = new HashMap<>();
        errorResponse.put("error", "Bad request.");
        errorResponse.put("message", safeMessage(ex));
        errorResponse.put("path", req.getRequestURI());
        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    /**
     * Honor the status code carried by {@link ResponseStatusException} —
     * this is the canonical way controllers throw HTTP errors (401, 403,
     * 404, etc.). Without this, the catch-all {@link #handleAllUncaughtException}
     * below was wrapping every {@code ResponseStatusException} as a 500,
     * masking real auth + not-found responses as server errors.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex, HttpServletRequest req) {
        HttpStatusCode status = ex.getStatusCode();
        // 4xx is expected client error; log at WARN. 5xx (rare via this path)
        // gets ERROR with stack trace.
        if (status.is5xxServerError()) {
            log.error("ResponseStatus exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        } else {
            log.warn("Client error {} on {} {}: {}", status.value(),
                    req.getMethod(), req.getRequestURI(), ex.getReason());
        }

        Map<String, String> errorResponse = new HashMap<>();
        errorResponse.put("error", status.is4xxClientError() ? "Client error." : "Server error.");
        errorResponse.put("message", ex.getReason() != null ? ex.getReason() : ex.getClass().getSimpleName());
        errorResponse.put("path", req.getRequestURI());
        return new ResponseEntity<>(errorResponse, status);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleAllUncaughtException(Exception ex, HttpServletRequest req) {
        // Catch-all for anything that escaped the more-specific handlers above.
        // ResponseStatusException is handled separately so 401/403/404/etc.
        // surface with their real status codes — see handleResponseStatus.
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);

        Map<String, String> errorResponse = new HashMap<>();
        errorResponse.put("error", "An unexpected error occurred.");
        errorResponse.put("message", safeMessage(ex));
        errorResponse.put("path", req.getRequestURI());
        return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private String safeMessage(Exception ex) {
        if (ex == null) return "Unknown error";
        String msg = ex.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : ex.getClass().getSimpleName();
    }
}