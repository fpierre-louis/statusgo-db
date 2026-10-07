package io.sitprep.sitprepapi.readiness;

/**
 * The DB {@code state} column of {@code household_readiness_item_state}.
 * DONE + NOT_RELEVANT are HOUSEHOLD rows; SKIPPED + REMIND_LATER are USER
 * rows (V98 CHECK {@code ck_hris_state_scope}).
 */
public enum ItemStateKind {
    DONE(true),
    NOT_RELEVANT(true),
    SKIPPED(false),
    REMIND_LATER(false);

    private final boolean householdScoped;

    ItemStateKind(boolean householdScoped) { this.householdScoped = householdScoped; }

    public boolean householdScoped() { return householdScoped; }

    /** DB scope value for this state: {@code HOUSEHOLD} or {@code USER}. */
    public String rowScope() { return householdScoped ? HouseholdReadinessItemState.SCOPE_HOUSEHOLD : HouseholdReadinessItemState.SCOPE_USER; }
}
