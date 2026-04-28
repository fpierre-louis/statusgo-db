package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * Read shape for one "with me" claim. Mirrors the FE's ref tuple shape so
 * the swap from local cache to API is a one-to-one substitution.
 *
 * <pre>
 * { id, supervisorRef: { kind, id, email? },
 *      accompaniedRef: { kind, id, email? },
 *      since, pending }
 * </pre>
 */
public record HouseholdAccompanimentDto(
        Long id,
        Ref supervisorRef,
        Ref accompaniedRef,
        Instant since,
        boolean pending
) {
    public record Ref(String kind, String id, String email) {}
}
