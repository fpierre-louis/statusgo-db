package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * Read shape for one "with me" claim. Mirrors the FE's ref tuple shape so
 * the swap from local cache to API is a one-to-one substitution.
 *
 * <pre>
 * { id, supervisorRef: { kind, id, email? },
 *      accompaniedRef: { kind, id, email? },
 *      since, pending, stale }
 * </pre>
 *
 * <p>{@code stale} (2026-10-09, household drawer Q6): true only while a
 * check-in is running and this claim was made BEFORE it started
 * ({@code since < StatusRollups.anchorFor(group)}). A stale "with me" counts
 * nobody safe — the rollups apply the same rule
 * ({@link io.sitprep.sitprepapi.service.StatusRollups#accompanimentCounts}) —
 * so a client must not present it as a current fact. Always false in calm.</p>
 */
public record HouseholdAccompanimentDto(
        Long id,
        Ref supervisorRef,
        Ref accompaniedRef,
        Instant since,
        boolean pending,
        boolean stale
) {
    /** A claim with no check-in context (a fresh claim is never stale). */
    public HouseholdAccompanimentDto(Long id, Ref supervisorRef, Ref accompaniedRef,
                                     Instant since, boolean pending) {
        this(id, supervisorRef, accompaniedRef, since, pending, false);
    }

    public record Ref(String kind, String id, String email) {}
}
