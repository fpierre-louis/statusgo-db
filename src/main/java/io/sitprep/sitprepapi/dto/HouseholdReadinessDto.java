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
     * Server-computed overall readiness percent for the household, based on
     * Global Readiness Engine pillar scores rather than frontend math.
     */
    private int readinessPercent;

    /**
     * Server-authored pillar scores. Frontend rings and progress displays
     * should render these directly.
     */
    private java.util.List<ReadinessDtos.PillarScoreDto> pillarScores;

    /**
     * Compatibility copy of the readiness pulse while the frontend finishes
     * migrating from /readiness.pulse to /readiness.household.pulse.
     */
    private ReadinessDtos.PulseDto pulse;

    /**
     * Canonical status label for this household — UNKNOWN | INJURED | HELP |
     * CHECK_IN | SAFE. Derived by the Global Readiness Engine from the SAME
     * Phase 1 accountability rollup the member-view ships
     * ({@code StatusRollups.dominantStatus} — zero duplicated aggregation).
     */
    private String dominantStatus;

    /**
     * Communications/Contacts pillar evaluation for this household's head:
     * actionable gaps (out-of-area contact, missing phone numbers,
     * meeting-place tiers) + server-authored recommendations. Computed over
     * the Phase 3 hardened DTO contracts.
     */
    private ReadinessDtos.CommsReadinessDto comms;
}
