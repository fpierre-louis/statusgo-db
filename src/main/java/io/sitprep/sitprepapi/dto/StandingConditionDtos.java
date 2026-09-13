package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;

/**
 * Wire shapes for Household Standing Conditions.
 *
 * <p>These are PRIVATE household data and are deliberately absent from
 * {@code PublicActivationDtos}. A bearer-link holder learns what the household
 * wants them to do and where to go — not the household's internal operating
 * state. See T-77: public APIs use explicit allowlisted recipient DTOs, so this
 * stays out by construction rather than by remembering to strip it.
 */
public final class StandingConditionDtos {

    private StandingConditionDtos() {}

    /**
     * @param updatedByName display name of whoever last touched it, resolved
     *                      server-side so the client never has to look up a
     *                      person to render "Updated by Dana".
     */
    public record StandingConditionDto(
            Long id,
            String category,
            String title,
            String instruction,
            String status,
            Instant createdAt,
            Instant updatedAt,
            String updatedByEmail,
            String updatedByName,
            Instant clearedAt,
            String clearedByEmail
    ) {
        public boolean active() { return !"CLEARED".equalsIgnoreCase(status); }
    }

    /** Create/update payload. Lifecycle is NOT settable here — clearing has its own verb. */
    public record StandingConditionRequest(
            String category,
            String title,
            String instruction
    ) {}

    /**
     * @param asOf when the server assembled this list, so a cached or printed
     *             copy can say how old it is instead of implying "current".
     */
    public record StandingConditionsDoc(
            List<StandingConditionDto> active,
            Instant asOf
    ) {}
}
