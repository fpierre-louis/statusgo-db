package io.sitprep.sitprepapi.practice;

/** What a Practice content item is. Keys are unique across both kinds. */
public enum PracticeKind {
    /** Choose-your-path household rehearsal, run by an adult. */
    ADULT_SCENARIO,
    /** Short activity an adult facilitates while the family takes part. */
    FAMILY_ACTIVITY,
    /**
     * One protective capability a household can learn, rehearse, plan and keep
     * (Practice redesign BE-04): the instructional spine scenarios, drills,
     * check-ins and if-then plan lines point at. Not a run: nothing is started.
     */
    SKILL
}
