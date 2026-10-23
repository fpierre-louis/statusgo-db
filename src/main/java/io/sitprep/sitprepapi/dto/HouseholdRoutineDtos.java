package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;

/** Wire shapes for household routines (EXEC-R1). No role, turn, child or practice data exists here. */
public final class HouseholdRoutineDtos {

    private HouseholdRoutineDtos() {}

    /** One slot value the household saved. */
    public record SlotValueDto(String key, String value, String refId) {}

    /** A saved routine, as it rides the household plan document and the offer. */
    public record HouseholdRoutineDto(String routineKey, String hazard, String title, String ifText,
                                      String thenText, List<SlotValueDto> slots, int contentVersion,
                                      Instant updatedAt) {}

    /** One pickable value; {@code refId} is set when it came from the household's plan or roster. */
    public record OptionDto(String value, String refId) {}

    public record SlotOfferDto(String key, String source, List<OptionDto> options, String fallback,
                               boolean allowText) {}

    public record RoutineOfferDto(String routineKey, int contentVersion, String hazard, String title,
                                  String ifText, String thenTemplate, List<SlotOfferDto> slots,
                                  HouseholdRoutineDto saved, boolean canSave, boolean preview) {}
}
