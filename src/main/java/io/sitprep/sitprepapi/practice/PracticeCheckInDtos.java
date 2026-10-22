package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.practice.ScenarioDtos.ChoiceDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.SuppressionDto;

import java.time.Instant;
import java.util.List;

/**
 * Wire shapes for check-ins and server skill state (Practice redesign FR-7). The
 * correct flag is not a field of any record here, by design.
 */
public final class PracticeCheckInDtos {

    private PracticeCheckInDtos() {}

    public static final int SCHEMA_VERSION = 1;

    /** One due card. {@code choices} are label-only; which one is right never leaves the server. */
    public record CardDto(String checkinKey, int version, String skillKey, String prompt,
                          List<ChoiceDto> choices, boolean objective) {}

    /** Up to three cards, all one hazard, most overdue first. Suppressed: no cards, and why. */
    public record DueDto(int schemaVersion, String hazard, String hazardLabel, List<CardDto> cards,
                         SuppressionDto suppression) {}

    public record AnswerRequest(String householdId, String choiceKey) {}

    /** {@code explanation} is the fuller explanation only when the choice was not the right one, else null. */
    public record AnswerDto(long answerId, String remember, String explanation) {}

    public record ConfidenceRequest(String confidence) {}

    public record SkillStateDto(String skillKey, String status, Instant lastPracticedAt) {}

    public record SkillStatesDto(int schemaVersion, List<SkillStateDto> states) {}
}
