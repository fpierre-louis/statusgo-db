package io.sitprep.sitprepapi.dto;

/**
 * Combined response for {@code POST /api/households/{id}/check-in/weekly}
 * — the actor's just-saved event plus the freshly recomputed weekly
 * roster. Returning both in one round-trip lets the FE swap straight
 * from the prompt scene to the variable-reward scene without a second
 * fetch.
 */
public record WeeklyCheckInResultDto(
        HouseholdEventDto event,
        WeeklyCheckInSummaryDto summary
) {}
