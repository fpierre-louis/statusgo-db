package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.readiness.ActiveResponseResolver.AlertHeadsUp;
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

    /** An official alert at home, as a nudge above the content (EXEC-H1). */
    public record AlertHeadsUpDto(String title, String detail, ReadinessAction action, Map<String, String> params) {
        static AlertHeadsUpDto of(AlertHeadsUp h) {
            return h == null ? null : new AlertHeadsUpDto(h.title(), h.detail(), h.action(),
                    h.params() == null ? Map.of() : h.params());
        }
    }

    /**
     * One catalog row. {@code localNote} (EXEC-I3) is a quiet line when the
     * scenario rehearses a hazard the household's area is known for, else null;
     * such rows also come first, highest tier first.
     */
    public record ScenarioCardDto(String key, int version, String title, String summary,
                                  Integer estimatedMinutes, boolean preview, String localNote) {}

    /** "Continue where you left off". */
    public record InProgressDto(long runId, String scenarioKey, String title, int stepNumber, String nodeTitle) {}

    public record CatalogDto(int schemaVersion, boolean practiceEnabled, SuppressionDto suppression,
                             List<ScenarioCardDto> scenarios, List<InProgressDto> inProgress,
                             AlertHeadsUpDto alertHeadsUp) {}

    public record ScenarioDetailDto(int schemaVersion, String key, int version, String title, String summary,
                                    String objective, Integer estimatedMinutes, List<SourceDto> sources,
                                    boolean preview, SuppressionDto suppression, Long inProgressRunId,
                                    AlertHeadsUpDto alertHeadsUp) {}

    public record ChoiceDto(String key, String label) {}

    /** One step. {@code scene} / {@code messages}: optional story touches (EXEC-I5), null when the content has none. */
    public record NodeDto(String key, String title, String body, String prompt, List<ChoiceDto> choices,
                          String scene, List<MessageDto> messages, MediaDto media) {}

    /**
     * Manifest-resolved media (EXEC-M). URLs come only from the server's
     * allow-listed manifest, never from content. {@code srcSet} is the
     * responsive JPEG set; {@code posterUrl} is set for clips.
     */
    public record MediaDto(String kind, String assetSha256, String url, String srcSet, Integer width, Integer height,
                           String focal, Integer durationMs, String posterUrl, String alt) {}

    public record MessageDto(String from, String text, boolean outgoing) {}

    /**
     * The choice just committed and its calm reflection (R4 beat 3):
     * {@code consequence} (what happened next) then {@code feedback} (why it
     * matters). {@code consequence} is null for content without it (v1), and
     * the client then shows {@code feedback} alone.
     */
    public record CommittedDto(String nodeKey, String nodeTitle, String choiceKey, String choiceLabel,
                               String consequence, String feedback, MediaDto media) {}

    public record NextStepDto(ReadinessAction action, Map<String, String> params,
                              String prompt, String label, String note) {}

    /**
     * One decision in "Your path" (EXEC-I2): the prompt and the label picked,
     * plus the step's title and EVERY option in authored order with its
     * reviewed consequence and why, {@code chosen} on exactly one. Showing the
     * options adds no copy: each line is already in the reviewed version.
     * Nothing here ranks the options; the client marks the pick in words.
     */
    public record PathStepDto(String prompt, String choiceLabel, String nodeTitle, List<PathOptionDto> options) {}

    public record PathOptionDto(String key, String label, String consequence, String feedback, boolean chosen,
                                MediaDto media) {}

    /** Exactly three sections: Strong choices, Worth practicing, Next family step. No score. */
    public record DebriefDto(String outcomeTitle, String outcomeBody, List<String> strongChoices,
                             List<String> worthPracticing, NextStepDto nextStep, List<PathStepDto> path,
                             MediaDto outcomeMedia) {}

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
