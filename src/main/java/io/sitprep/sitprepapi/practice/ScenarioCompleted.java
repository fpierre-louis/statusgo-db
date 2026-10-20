package io.sitprep.sitprepapi.practice;

/**
 * A household finished a published scenario (EXEC-I4). A plain Spring
 * application event: Practice announces it and never calls into the token
 * system itself (PracticeInvariantTest keeps Practice free of outside writes);
 * the gamification listener turns it into a token evaluation after commit.
 */
public record ScenarioCompleted(long runId, String householdId, String scenarioKey) {}
