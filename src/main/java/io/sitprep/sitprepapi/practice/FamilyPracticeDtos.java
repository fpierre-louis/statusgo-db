package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.practice.ScenarioDtos.AlertHeadsUpDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.NextStepDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.SourceDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.SuppressionDto;

import java.time.Instant;
import java.util.List;

/** Client shapes for adult-facilitated Family Practice activities. */
public final class FamilyPracticeDtos {

    private FamilyPracticeDtos() {}

    public static final int SCHEMA_VERSION = 1;

    public record ActivityCardDto(String key, int version, String title, String summary,
                                  Integer estimatedMinutes, List<String> ageGuidance,
                                  boolean preview, Instant completedAt) {}

    public record CatalogDto(int schemaVersion, boolean practiceEnabled, SuppressionDto suppression,
                             List<ActivityCardDto> activities, AlertHeadsUpDto alertHeadsUp) {}

    public record PileDto(String key, String label) {}

    public record ItemDto(String key, String label, String artKey) {}

    public record ActivityDto(String type, String prompt, String reminderQuestion,
                              String recapTitle, String recapBody,
                              List<PileDto> piles, List<ItemDto> items, NextStepDto nextStep,
                              String finishLabel, String summaryTitle, String summaryBody,
                              // TOGETHER only (FR-8); null / empty for ITEM_SORT.
                              String recommendedStyle, List<RoleDto> roles, List<TurnDto> turns,
                              String remember, String talkAboutIt, List<String> followUps,
                              String topic,
                              // TOGETHER only: manifest-resolved setup hero, null otherwise.
                              ScenarioDtos.MediaDto heroMedia) {}

    /** A role card; {@code adultOnly} comes from the server enum, never from content. */
    public record RoleDto(String key, String label, boolean adultOnly) {}

    public record TurnDto(String rhythm, String role, String roleLabel, String say, String grownUp, String ask) {}

    /** The household's newest unexpired Together session for this activity version. */
    public record ActiveSessionDto(long sessionId, String style, int turnIndex, Instant expiresAt) {}

    public record ActivityDetailDto(int schemaVersion, String key, int version, String contentHash,
                                    String title, String summary, String objective, Integer estimatedMinutes,
                                    List<String> ageGuidance, String facilitation, List<SourceDto> sources,
                                    ActivityDto activity, boolean preview, SuppressionDto suppression,
                                    Instant completedAt, AlertHeadsUpDto alertHeadsUp,
                                    ActiveSessionDto activeSession) {}

    /** The client proves which immutable content it completed; it sends no sorting choices. */
    public record CompleteRequest(Integer contentVersion, String contentHash) {}

    public record CompletionDto(int schemaVersion, String householdId, String activityKey,
                                int contentVersion, String contentHash, Instant completedAt,
                                List<String> debriefTags, NextStepDto nextStep) {}

    public record ProgressItemDto(String activityKey, int completedVersions, Instant lastCompletedAt) {}

    public record ProgressDto(int schemaVersion, String householdId, List<ProgressItemDto> activities) {}
}
