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
    /** {@code hazard} (a presentation tag) and {@code decisionCount} (longest path) are additive (redesign FR-2). */
    public record ScenarioCardDto(String key, int version, String title, String summary,
                                  Integer estimatedMinutes, boolean preview, String localNote,
                                  String hazard, Integer decisionCount) {}

    /** "Continue where you left off". */
    public record InProgressDto(long runId, String scenarioKey, String title, int stepNumber, String nodeTitle) {}

    public record CatalogDto(int schemaVersion, boolean practiceEnabled, SuppressionDto suppression,
                             List<ScenarioCardDto> scenarios, List<InProgressDto> inProgress,
                             AlertHeadsUpDto alertHeadsUp) {}

    public record ScenarioDetailDto(int schemaVersion, String key, int version, String title, String summary,
                                    String objective, Integer estimatedMinutes, List<SourceDto> sources,
                                    boolean preview, SuppressionDto suppression, Long inProgressRunId,
                                    AlertHeadsUpDto alertHeadsUp, String hazard, Integer decisionCount,
                                    /** INTRO still (EXEC-M3); null when the scenario has none. */
                                    MediaDto introMedia) {}

    /** {@code thumb}: CHOICE_THUMB still (EXEC-M3), identical in kind for every option of a node, null when the node has none. */
    public record ChoiceDto(String key, String label, MediaDto thumb) {}

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
                               String consequence, String feedback,
                               /** The committed node's explanation still; same for every choice, null when none (EXEC-M2). */
                               MediaDto explanationMedia,
                               List<OtherChoiceDto> otherChoices) {}

    /**
     * One option the household did NOT pick at the step just committed
     * (Practice redesign BE-03), for "What if I picked something else?". Sent
     * only after the choice is locked, and only for that step: no {@code next}
     * edge and no tag polarity, so nothing about the rest of the graph leaks.
     * It is the same reviewed copy the completed debrief's path already shows.
     */
    public record OtherChoiceDto(String key, String label, String consequence, String feedback) {}

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

    public record PathOptionDto(String key, String label, String consequence, String feedback, boolean chosen) {}

    /**
     * No score. {@code oneThing} (additive, redesign BE-02) is the single
     * improvement the debrief leads with — the line behind {@code nextStep} —
     * so a client can say exactly one thing without guessing which of
     * {@code worthPracticing} matched. Null when nothing was worth practicing.
     */
    public record DebriefDto(String outcomeTitle, String outcomeBody, List<String> strongChoices,
                             List<String> worthPracticing, NextStepDto nextStep, List<PathStepDto> path,
                             MediaDto outcomeMedia, String oneThing,
                             /** EXEC-R1: the household routine this scenario offers to save, when servable; else null. */
                             PracticeRoutines.RoutineOfferDto routineOffer) {}

    /**
     * One run as the client draws it. {@code contentStatus} says what the run
     * may show: AVAILABLE / RETIRED render normally; DISABLED / MISSING carry
     * no content at all.
     */
    public record RunDto(int schemaVersion, long runId, String scenarioKey, int contentVersion, String title,
                         String status, String contentStatus, boolean preview, int stepNumber,
                         NodeDto node, CommittedDto lastCommit, boolean atOutcome, DebriefDto debrief,
                         SuppressionDto suppression, Instant startedAt, Instant completedAt,
                         /** Decisions taken + the longest path still ahead: the "of N" (redesign FR-2). */
                         Integer decisionsTotal) {}

    public record StartRequest(String householdId) {}

    public record CommitRequest(String nodeKey, String choiceKey) {}

    public record ScenarioProgressDto(String scenarioKey, int completedCount, Instant lastCompletedAt,
                                      Long inProgressRunId) {}

    public record ProgressDto(int schemaVersion, String householdId, List<ScenarioProgressDto> scenarios) {}

    /** A hazard where the household lives (risk profile), highest risk first. */
    public record LocalHazardDto(String hazard, String label, String tier) {}

    /**
     * The Practice home's Today (redesign FR-5; handoff TODAY_FLOW), composed by
     * the server from data it holds: {@code reentry} FIRST | GAP | RECENT (the
     * header's subtitle), at most one {@code recommended} story, the newest run
     * to {@code continueRun}, and the {@code localHazards}. The drill block is the
     * client's (drill cadences live in its catalog). Nothing here is a count.
     */
    public record TodayDto(int schemaVersion, String reentry, ScenarioCardDto recommended, InProgressDto continueRun,
                           List<LocalHazardDto> localHazards, SuppressionDto suppression, RefreshDto refresh) {}

    /**
     * A due check-in outranks the story (TODAY_FLOW rule 1): the hazard, its label, the first
     * card's prompt and how many cards are due. Null when nothing is due or Practice waits.
     */
    public record RefreshDto(String hazard, String hazardLabel, String title, int count) {}
}
