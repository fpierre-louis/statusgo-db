package io.sitprep.sitprepapi.practice;

import java.util.List;

/** Lets tests outside {@code practice} build a catalog around the fixture ROUTINE (the fixtures are package-private). */
public final class PracticeRoutineTestSupport {

    private PracticeRoutineTestSupport() {}

    public static PracticeAvailabilityService availability(PracticeCatalog catalog, PracticeContentControlRepo controls,
                                                           boolean preview, java.time.Clock clock) {
        return new PracticeAvailabilityService(catalog, controls, true, preview, clock);
    }

    public static PracticeCatalog catalogWithFixtureRoutine(String key, String offeredScenario) {
        return PracticeCatalog.of(List.of(PracticeFixtures.version(PracticeFixtures.routine(key, offeredScenario, null))));
    }
}
