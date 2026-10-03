package io.sitprep.sitprepapi.gamification;

/** What happened. Household events re-evaluate every household token. */
public enum TokenEventType {
    // Household — any change to the household's plan records
    DRILL_COMPLETED(true),
    MEETING_PLACE_CHANGED(true),
    CONTACTS_CHANGED(true),
    EVACUATION_PLAN_CHANGED(true),
    SUPPLIES_CHANGED(true),

    // Individual
    COMMUNITY_POST_CREATED(false),
    ASK_QUESTION_CREATED(false),
    ASK_TIP_CREATED(false),
    ASK_ANSWER_CREATED(false),
    ASK_ANSWER_ACCEPTED(false),
    ASK_VOTE_CAST(false),
    HAZARD_REPORTED(false),
    HAZARD_VOTED(false),
    MAP_CONFIRMED(false),
    POST_CONFIRMED(false);

    private final boolean household;

    TokenEventType(boolean household) {
        this.household = household;
    }

    public boolean isHousehold() {
        return household;
    }
}
