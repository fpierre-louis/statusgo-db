package io.sitprep.sitprepapi.dto;

/**
 * Error half of the {@link ApiResponse} envelope. Only populated when an
 * endpoint surfaces a recoverable failure to the client (validation, partial
 * outage, etc.). Hard 4xx/5xx still use Spring's ResponseStatusException +
 * ControllerAdvice path — this is for cases where the BE wants to ship a 200
 * with a structured error description.
 */
public record ApiError(String code, String message) {}
