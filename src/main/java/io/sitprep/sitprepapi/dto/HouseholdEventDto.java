package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Read-shape for a household activity event. Joined against UserInfo at
 * service time to resolve the actor's display name + avatar so the
 * frontend can render the "Bobby marked Safe" row without a separate
 * profile fetch.
 *
 * <p>{@code payload} is kind-specific. Examples:
 * <ul>
 *   <li>{@code status-changed} → {@code { status: "SAFE" }}</li>
 *   <li>{@code checkin-started} → {@code {}}</li>
 *   <li>{@code with-claim} → {@code { subjectEmail, subjectName }}</li>
 * </ul></p>
 */
public record HouseholdEventDto(
        Long id,
        String householdId,
        String kind,
        Instant at,
        String actorEmail,
        String actorName,
        String actorProfileImageUrl,
        Map<String, Object> payload
) {}
