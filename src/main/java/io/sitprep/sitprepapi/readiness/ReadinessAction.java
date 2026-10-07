package io.sitprep.sitprepapi.readiness;

/**
 * Semantic client actions (CONTRACT.md §5). Never a route string — the FE
 * {@code readinessActions.js} owns the action → route map.
 */
public enum ReadinessAction {
    OPEN_ESSENTIALS,
    OPEN_EMERGENCY_CONTACTS,
    OPEN_HOME_STOCKPILE,
    OPEN_POWER_OUTAGE_PLAYBOOK,
    OPEN_EVACUATION_PLAN,
    OPEN_ALERT_PRESETS,
    OPEN_ALERT_PREFERENCES,
    START_SELF_REPORT_STEP,
    OPEN_DRILL,
    OPEN_HOUSEHOLD_PLAN,
    OPEN_PRINTABLE_PLAN,
    OPEN_HOUSEHOLD_LOCATION,
    OPEN_GO_BAG,
    OPEN_FOOD_PLAN,
    OPEN_HAZARD_GUIDE,
    OPEN_ACTIVE_SITUATION,
    OPEN_CHECK_IN,
    OPEN_ACTIVE_ALERTS
}
