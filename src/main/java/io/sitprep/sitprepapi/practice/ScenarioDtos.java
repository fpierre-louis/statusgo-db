package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.readiness.ReadinessAction;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Client-ready shapes for Practice scenarios. The FE renders these; it holds
 * no graph, no tag polarity and no next-step rule. Choice reflections are
 * only ever sent for a COMMITTED choice, never ahead of the commit.
 */
public final class ScenarioDtos {

    private ScenarioDtos() {}

    public static final int SCHEMA_VERSION = 1;

    public record SourceDto(String label, String url) {}

    /** Why Practice is waiting, and (when known) where the live situation is. */
    public record SuppressionDto(String reason, String title, String detail,
                                 ReadinessAction action, Map<String, String> params) {
        static SuppressionDto of(PracticeSuppressionService.Suppression s) {
            return s == null ? null : new SuppressionDto(s.reason().name(), s.title(), s.detail(),
                    s.action(), s.params() == null ? Map.of() : s.params());
        }
    }

    public record ScenarioCardDto(String key, int version, String title, String summary,
                                  Integer estimatedMinutes, boolean preview) {}

    /** "Continue where you left off". */
    public record InProgressDto(long runId, String scenarioKey, String title, int stepNumber, String nodeTitle) {}

    public record CatalogDto(int schemaVersion, boolean practiceEnabled, SuppressionDto suppression,
                             List<ScenarioCardDto> scenarios, List<InProgressDto> inProgress) {}

    public record ScenarioDetailDto(int schemaVersion, String key, int version, String title, String summary,
                                    String objective, Integer estimatedMinutes, List<SourceDto> sources,
                                    boolean preview, SuppressionDto suppression, Long inProgressRunId) {}

    public record ChoiceDto(String key, String label) {}

    public record NodeDto(String key, String title, String body, String prompt, List<ChoiceDto> choices) {}

    /** The choice just committed and its calm reflection (R4 beat 3). */
    public record CommittedDto(String nodeKey, String nodeTitle, String choiceKey, String choiceLabel,
                               String feedback) {}

    public record NextStepDto(ReadinessAction action, Map<String, String> params,
                              String prompt, String label, String note) {}

    public record PathStepDto(String prompt, String choiceLabel) {}

    /** Exactly three sections: Strong choices, Worth practicing, Next family step. No score. */
    public record DebriefDto(String outcomeTitle, String outcomeBody, List<String> strongChoices,
                             List<String> worthPracticing, NextStepDto nextStep, List<PathStepDto> path) {}

    /**
     * One run as the client draws it. {@code contentStatus} says what the run
     * may show: AVAILABLE / RETIRED render normally; DISABLED / MISSING carry
     * no content at all.
     */
    public record RunDto(int schemaVersion, long runId, String scenarioKey, int contentVersion, String title,
                         String status, String contentStatus, boolean preview, int stepNumber,
                         NodeDto node, CommittedDto lastCommit, boolean atOutcome, DebriefDto debrief,
                         SuppressionDto suppression, Instant startedAt, Instant completedAt) {}

    public record StartRequest(String householdId) {}

    public record CommitRequest(String nodeKey, String choiceKey) {}

    public record ScenarioProgressDto(String scenarioKey, int completedCount, Instant lastCompletedAt,
                                      Long inProgressRunId) {}

    public record ProgressDto(int schemaVersion, String householdId, List<ScenarioProgressDto> scenarios) {}
}
