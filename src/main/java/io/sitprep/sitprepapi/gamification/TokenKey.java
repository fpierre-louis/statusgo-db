package io.sitprep.sitprepapi.gamification;

/**
 * Every Readiness Token. The ledger stores {@link #name()} in {@code token_key},
 * so a constant is never renamed once shipped — retire it and add a new one.
 *
 * <p>Plan Kept Fresh is held out of v1: {@code POST /plan/confirm} exists but no
 * screen calls it, so the token could not be earned
 * (docs/epics/readiness_tokens/README.md).</p>
 */
public enum TokenKey {
    // Household
    PLAN_ARCHITECT,
    MEETING_POINT,
    CONTACT_CIRCLE,
    DRILL_CREW,
    PRACTICE_CADENCE,
    STOCKPILE_STEWARD,
    HOUSEHOLD_READY,

    // Individual
    FIRST_NEIGHBOR_SIGNAL,
    LOCAL_HAZARD_REPORTER,
    GROUND_TRUTH,
    HELPFUL_QUESTION,
    PREP_TIP_SHARER,
    ANSWERED_THE_CALL,
    TRUSTED_ANSWER,
    HELPING_HAND
}
