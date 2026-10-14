// src/main/java/io/sitprep/sitprepapi/dto/GroupReadinessSummaryDto.java
package io.sitprep.sitprepapi.dto;

import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
public class GroupReadinessSummaryDto {

    private String groupId;
    private String groupName;
    private String groupType;

    private int totalMembers;

    private int membersWithDemographic;
    private int membersWithMealPlan;
    private int membersWithEvacuationPlan;
    private int membersWithEmergencyContacts;

    private int fullyReadyMembers;

    /**
     * Audit ST-06 / D15: the four essentials (Home's rule) counted per
     * member's base household, in Home's order, with admin labels. {@code done}
     * of {@code total} members have that essential in their household.
     */
    private List<EssentialCount> essentials;

    /** Members with every essential, as a whole percent; null when the group has no members. */
    private Integer readinessPercent;

    /** Members with no base household yet (they count as nothing done). */
    private int membersWithoutHousehold;

    public record EssentialCount(String key, String label, int done, int total) {}

    // Readiness only — status is handled on a different page
    private int membersCurrentlySafe;
    private int membersNeedingAssistance;

    // Optional breakdown by status (if you still want it server-side)
    private Map<String, Integer> statusCounts;

    // When this summary was computed
    private Instant generatedAt;

    // 🔹 NEW: Household subgroup readiness
    private int totalHouseholdGroups;
    private int totalHouseholdMembers;
    private List<HouseholdReadinessDto> households;
}