package io.sitprep.sitprepapi.readiness;

import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.domain.EvacuationPlan;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.MealPlanData;
import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.readiness.EssentialsReadinessService.EssentialsResult;
import io.sitprep.sitprepapi.repo.DemographicRepo;
import io.sitprep.sitprepapi.repo.EmergencyContactGroupRepo;
import io.sitprep.sitprepapi.repo.EvacuationPlanRepo;
import io.sitprep.sitprepapi.repo.MealPlanDataRepo;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Parity with Home's essentials rule (FE essentialsCatalog.js
 * normalizeFromMePlans + CHECKS, read through /me/plans + /me.household).
 */
class EssentialsReadinessServiceTest {

    static final String HH = "hh-1";
    static final String ME = "member@example.com";

    final MeetingPlaceRepo meetingPlaces = mock(MeetingPlaceRepo.class);
    final EvacuationPlanRepo evacPlans = mock(EvacuationPlanRepo.class);
    final EmergencyContactGroupRepo contactGroups = mock(EmergencyContactGroupRepo.class);
    final MealPlanDataRepo mealPlans = mock(MealPlanDataRepo.class);
    final DemographicRepo demographics = mock(DemographicRepo.class);
    final UserInfoRepo users = mock(UserInfoRepo.class);
    final EssentialsReadinessService service = new EssentialsReadinessService(
            meetingPlaces, evacPlans, contactGroups, mealPlans, demographics, users);

    Group household;

    @BeforeEach
    void setUp() {
        household = new Group();
        household.setGroupId(HH);
        household.setGroupType("Household");
        when(meetingPlaces.findByHouseholdId(anyString())).thenReturn(List.of());
        when(evacPlans.findByHouseholdId(anyString())).thenReturn(List.of());
        when(contactGroups.findByHouseholdId(anyString())).thenReturn(List.of());
        when(mealPlans.findFirstByHouseholdId(anyString())).thenReturn(Optional.empty());
        when(demographics.findFirstByHouseholdIdOrderByIdDesc(anyString())).thenReturn(Optional.empty());
    }

    static Demographic demo(int adults, int dogs) {
        Demographic d = new Demographic();
        d.setAdults(adults);
        d.setDogs(dogs);
        return d;
    }

    void allFourPresent() {
        when(meetingPlaces.findByHouseholdId(HH)).thenReturn(List.of(new MeetingPlace()));
        when(evacPlans.findByHouseholdId(HH)).thenReturn(List.of(new EvacuationPlan()));
        when(contactGroups.findByHouseholdId(HH)).thenReturn(List.of(new EmergencyContactGroup()));
        when(mealPlans.findFirstByHouseholdId(HH)).thenReturn(Optional.of(new MealPlanData()));
        when(demographics.findFirstByHouseholdIdOrderByIdDesc(HH)).thenReturn(Optional.of(demo(2, 0)));
    }

    @Test
    void allFiveChecksPassIsComplete() {
        allFourPresent();
        EssentialsResult r = service.evaluate(household, ME, false);
        assertThat(r.complete()).isTrue();
        assertThat(r.done()).isEqualTo(4);
        assertThat(r.nextKey()).isNull();
    }

    @Test
    void meetingPlacesWithoutRoutesLeavesEvacuationNotDone() {
        allFourPresent();
        when(evacPlans.findByHouseholdId(HH)).thenReturn(List.of());
        EssentialsResult r = service.evaluate(household, ME, false);
        assertThat(r.evacuation()).isFalse();
        assertThat(r.nextKey()).isEqualTo("evacuation");
    }

    @Test
    void anEmptyContactGroupStillPasses() {
        allFourPresent();
        EmergencyContactGroup empty = new EmergencyContactGroup();
        empty.setContacts(new java.util.ArrayList<>());
        when(contactGroups.findByHouseholdId(HH)).thenReturn(List.of(empty));
        assertThat(service.evaluate(household, ME, false).contacts()).isTrue();
    }

    @Test
    void aMealPlanRowWithoutMenusStillPasses() {
        allFourPresent();
        MealPlanData bare = new MealPlanData();   // no menus, no options
        when(mealPlans.findFirstByHouseholdId(HH)).thenReturn(Optional.of(bare));
        assertThat(service.evaluate(household, ME, false).mealPlan()).isTrue();
    }

    @Test
    void demographicsAllZerosFails() {
        allFourPresent();
        when(demographics.findFirstByHouseholdIdOrderByIdDesc(HH)).thenReturn(Optional.of(demo(0, 0)));
        EssentialsResult r = service.evaluate(household, ME, false);
        assertThat(r.demographics()).isFalse();
        assertThat(r.nextKey()).isEqualTo("demographics");
    }

    @Test
    void petsAloneCountAsHeadCount() {
        allFourPresent();
        when(demographics.findFirstByHouseholdIdOrderByIdDesc(HH)).thenReturn(Optional.of(demo(0, 1)));
        assertThat(service.evaluate(household, ME, false).demographics()).isTrue();
    }

    @Test
    void nullHouseholdFailsDemographics() {
        EssentialsResult r = service.evaluate(null, ME, false);
        assertThat(r.demographics()).isFalse();
        assertThat(r.done()).isZero();
        assertThat(r.nextKey()).isEqualTo("demographics");
    }

    @Test
    void nextKeyFollowsHomeOrder() {
        allFourPresent();
        when(mealPlans.findFirstByHouseholdId(HH)).thenReturn(Optional.empty());
        when(contactGroups.findByHouseholdId(HH)).thenReturn(List.of());
        // demographics done → mealPlan is next even though contacts is also open.
        assertThat(service.evaluate(household, ME, false).nextKey()).isEqualTo("mealPlan");
    }

    @Test
    void ownerFallbackOnlyForTheRequestersBaseHousehold() {
        when(contactGroups.findByOwnerEmailIgnoreCase(ME)).thenReturn(List.of(new EmergencyContactGroup()));
        when(meetingPlaces.findByOwnerEmail(ME)).thenReturn(List.of(new MeetingPlace()));
        when(evacPlans.findByOwnerEmail(ME)).thenReturn(List.of(new EvacuationPlan()));
        when(mealPlans.findFirstByOwnerEmailIgnoreCase(ME)).thenReturn(Optional.of(new MealPlanData()));
        when(demographics.findFirstByOwnerEmailIgnoreCaseOrderByIdDesc(ME)).thenReturn(Optional.of(demo(1, 0)));

        assertThat(service.evaluate(household, ME, true).complete()).isTrue();

        EssentialsResult notBase = service.evaluate(household, ME, false);
        assertThat(notBase.done()).isZero();
    }

    @Test
    void householdRowsWinOverOwnerRows() {
        allFourPresent();
        service.evaluate(household, ME, true);
        verify(contactGroups, never()).findByOwnerEmailIgnoreCase(anyString());
        verify(meetingPlaces, never()).findByOwnerEmail(anyString());
    }

    @Test
    void isRequesterBaseReadsUserInfo() {
        UserInfo u = new UserInfo();
        u.setBaseHouseholdId(HH);
        when(users.findByUserEmailIgnoreCase(ME)).thenReturn(Optional.of(u));
        assertThat(service.isRequesterBase(HH, ME)).isTrue();
        assertThat(service.isRequesterBase("other", ME)).isFalse();
        assertThat(service.isRequesterBase(HH, null)).isFalse();
    }
}
