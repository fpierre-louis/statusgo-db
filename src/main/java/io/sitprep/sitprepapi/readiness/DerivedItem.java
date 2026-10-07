package io.sitprep.sitprepapi.readiness;

import io.sitprep.sitprepapi.readiness.ReadinessCatalog.CatalogItem;

import java.time.Instant;

/**
 * One applicable catalog item after completion, freshness and overlays have
 * been derived for one household + requester. Internal; never serialized.
 *
 * @param householdState honored household row state (DONE only on MANUAL items), or null
 * @param userState      the requester's ACTIVE SKIPPED / REMIND_LATER row state, or null
 * @param userUntil      when that user state ends
 */
record DerivedItem(
        CatalogItem item,
        CompletionState completion,
        Instant completedAt,
        Freshness freshness,
        Instant reviewDueAt,
        ItemStateKind householdState,
        ItemStateKind userState,
        Instant userUntil
) {
    boolean notRelevant() { return householdState == ItemStateKind.NOT_RELEVANT; }

    boolean counted() { return !notRelevant(); }

    boolean countsDone() { return counted() && completion == CompletionState.COMPLETE; }

    boolean userSuppressed() { return userState != null; }

    /** Could be the next step: relevant, not snoozed, and either not done or due for a review. */
    boolean candidate() {
        return !notRelevant() && !userSuppressed()
                && (completion == CompletionState.INCOMPLETE || freshness == Freshness.REVIEW_DUE);
    }
}
