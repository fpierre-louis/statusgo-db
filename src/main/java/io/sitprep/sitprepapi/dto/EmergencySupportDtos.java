package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;

/**
 * Wire shapes for RC-3 Phase 2 — the Emergency Need Profile (Level 1) and the
 * prepared Support Plan (Level 2).
 *
 * <p>Note what is NOT here: no acceptance, no availability, no acknowledgement.
 * Those are Level 3 and their absence is the point — see
 * {@code EmergencySupportAssignment}'s javadoc.</p>
 */
public final class EmergencySupportDtos {

    private EmergencySupportDtos() {}

    /**
     * Operational communication needs. Values name what a HELPER MUST DO
     * DIFFERENTLY, never a diagnosis — which is how deaf, low-vision,
     * limited-English and cognitive-support needs are all representable without
     * SitPrep recording a condition.
     */
    public enum CommunicationNeed {
        /** Reach them by text; a voice call will not work. */
        TEXT_NOT_VOICE,
        /** Audible alerts alone will not reach them. */
        VISUAL_ALERTS,
        /** Small print will not be readable. */
        LARGE_TEXT_OR_AUDIO,
        /** Instructions must be short and concrete. */
        PLAIN_LANGUAGE,
        /** English-only instructions will not be understood. */
        INTERPRETER_OR_LANGUAGE
    }

    public record SupportProfileDto(
            String subjectType,
            String subjectId,
            boolean needsEvacuationAssistance,
            boolean cannotUseStairs,
            String mobilityNote,
            List<String> communicationNeeds,
            String preferredLanguage,
            boolean powerDependentEquipment,
            String equipmentNote,
            boolean refrigeratedMedication,
            boolean criticalMedication,
            boolean accessibleTransportNeeded,
            boolean serviceAnimal,
            String serviceAnimalNote,
            String supportNote,
            Instant updatedAt
    ) {}

    /**
     * A prepared helper. {@code helperName} is resolved server-side so a printed
     * or cached plan still names them when the contact row is unreachable.
     *
     * <p>There is no acknowledgement field. A client rendering this must say
     * "Primary support: Marcus" and must not say "Marcus is handling this".</p>
     */
    public record SupportAssignmentDto(
            String subjectType,
            String subjectId,
            String role,
            String helperType,
            String helperUserEmail,
            Long helperContactId,
            String helperName,
            String helperNote,
            Instant assignedAt
    ) {}

    /** Write shape — whole-profile upsert, never a field patch. */
    public record SupportProfileRequest(
            boolean needsEvacuationAssistance,
            boolean cannotUseStairs,
            String mobilityNote,
            List<String> communicationNeeds,
            String preferredLanguage,
            boolean powerDependentEquipment,
            String equipmentNote,
            boolean refrigeratedMedication,
            boolean criticalMedication,
            boolean accessibleTransportNeeded,
            boolean serviceAnimal,
            String serviceAnimalNote,
            String supportNote
    ) {}

    public record SupportAssignmentRequest(
            String helperType,
            String helperUserEmail,
            Long helperContactId,
            String helperNote
    ) {}
}
