package io.sitprep.sitprepapi.readiness;

/**
 * Only for COMPLETE steps with a review interval; null otherwise.
 * Staleness never undoes completion.
 */
public enum Freshness { CURRENT, REVIEW_SOON, REVIEW_DUE, UNKNOWN }
