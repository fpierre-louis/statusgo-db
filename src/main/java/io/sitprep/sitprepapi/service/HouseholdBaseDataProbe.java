package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.repo.DemographicRepo;
import io.sitprep.sitprepapi.repo.EmergencyContactGroupRepo;
import io.sitprep.sitprepapi.repo.EvacuationPlanRepo;
import io.sitprep.sitprepapi.repo.GoBagRepo;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.HouseholdManualMemberRepo;
import io.sitprep.sitprepapi.repo.HouseholdPetRepo;
import io.sitprep.sitprepapi.repo.MealPlanDataRepo;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import io.sitprep.sitprepapi.repo.OriginLocationRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * "Is this base household a disposable solo one?" — what provisioning
 * creates at sign-up: a Household whose only member is the caller and that
 * holds nothing. Any plan data, named person, pet, pending request or chat
 * post means it is a real household and is never abandoned implicitly.
 */
@Service
public class HouseholdBaseDataProbe {
    private final GroupRepo groupRepo;
    private final HouseholdManualMemberRepo manualRepo;
    private final HouseholdPetRepo petRepo;
    private final DemographicRepo demographicRepo;
    private final MealPlanDataRepo mealPlanRepo;
    private final EvacuationPlanRepo evacRepo;
    private final MeetingPlaceRepo meetingRepo;
    private final EmergencyContactGroupRepo contactGroupRepo;
    private final OriginLocationRepo originRepo;
    private final GoBagRepo goBagRepo;
    private final GroupPostRepo groupPostRepo;

    public HouseholdBaseDataProbe(GroupRepo groupRepo, HouseholdManualMemberRepo manualRepo, HouseholdPetRepo petRepo,
                         DemographicRepo demographicRepo, MealPlanDataRepo mealPlanRepo,
                         EvacuationPlanRepo evacRepo, MeetingPlaceRepo meetingRepo,
                         EmergencyContactGroupRepo contactGroupRepo, OriginLocationRepo originRepo,
                         GoBagRepo goBagRepo, GroupPostRepo groupPostRepo) {
        this.groupRepo = groupRepo;
        this.manualRepo = manualRepo;
        this.petRepo = petRepo;
        this.demographicRepo = demographicRepo;
        this.mealPlanRepo = mealPlanRepo;
        this.evacRepo = evacRepo;
        this.meetingRepo = meetingRepo;
        this.contactGroupRepo = contactGroupRepo;
        this.originRepo = originRepo;
        this.goBagRepo = goBagRepo;
        this.groupPostRepo = groupPostRepo;
    }

    @Transactional(readOnly = true)
    public boolean isDisposableSolo(String householdId, String email) {
        Group g = groupRepo.findByGroupId(householdId).orElse(null);
        if (g == null) return true; // dangling base id — nothing to strand
        if (!HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(g.getGroupType())) return false;
        for (String m : HouseholdCompositionService.accountEmails(g)) {
            if (!m.equals(email)) return false;
        }
        if (g.getPendingMemberEmails() != null && !g.getPendingMemberEmails().isEmpty()) return false;
        return manualRepo.findByHouseholdIdOrderByCreatedAtAsc(householdId).isEmpty()
                && petRepo.findByHouseholdIdOrderByCreatedAtAsc(householdId).isEmpty()
                && demographicRepo.findFirstByHouseholdIdOrderByIdDesc(householdId).isEmpty()
                && !mealPlanRepo.existsByHouseholdId(householdId)
                && evacRepo.findByHouseholdId(householdId).isEmpty()
                && meetingRepo.findByHouseholdId(householdId).isEmpty()
                && contactGroupRepo.findByHouseholdId(householdId).isEmpty()
                && originRepo.findByHouseholdId(householdId).isEmpty()
                && !goBagRepo.existsByHouseholdId(householdId)
                && groupPostRepo.countByGroupIdAndTimestampAfter(householdId, Instant.EPOCH) == 0;
    }
}
