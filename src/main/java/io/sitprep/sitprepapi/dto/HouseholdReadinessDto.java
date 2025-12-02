package io.sitprep.sitprepapi.dto;

import lombok.Data;

@Data
public class HouseholdReadinessDto {

    private String groupId;
    private String groupName;

    private String ownerName;
    private String ownerEmail;

    private int memberCount;

    private int membersWithDemographic;
    private int membersWithMealPlan;
    private int membersWithEvacuationPlan;
    private int membersWithEmergencyContacts;

    private int fullyReadyMembers;

    /**
     * Canonical status label for this household,
     * e.g. SAFE / HELP / CHECK_IN / UNKNOWN, based on members' statuses.
     */
    private String dominantStatus;
}