package io.sitprep.sitprepapi.readiness;

/**
 * Where a step's completion comes from. {@link #MANUAL} is the ONLY source
 * that accepts a household DONE row; every other source reads the real
 * domain and cannot be self-reported (409).
 */
public enum CompletionSource { MANUAL, CONTACTS, STOCKPILE, EVACUATION_METRIC, DRILL_LOG, PLAN_CONFIRMATION }
