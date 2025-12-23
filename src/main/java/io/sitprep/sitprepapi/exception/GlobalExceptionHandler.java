// src/main/java/io/sitprep/sitprepapi/exception/GlobalExceptionHandler.java
package io.sitprep.sitprepapi.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleAllUncaughtException(Exception ex, HttpServletRequest req) {
        // ✅ This is the main fix: you want the stack trace in logs
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);

        Map<String, String> errorResponse = new HashMap<>();
        errorResponse.put("error", "An unexpected error occurred.");
        // ✅ Never return null messages; makes Postman debugging way easier
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