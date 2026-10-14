package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.domain.EvacuationPlan;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.MealPlanData;
import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.GroupReadinessSummaryDto;
import io.sitprep.sitprepapi.readiness.EssentialsReadinessService;
import io.sitprep.sitprepapi.repo.DemographicRepo;
import io.sitprep.sitprepapi.repo.EmergencyContactGroupRepo;
import io.sitprep.sitprepapi.repo.EvacuationPlanRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.MealPlanDataRepo;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Audit ST-06 / D15 (lane B6): the org readiness rollup counts the four
 * essentials per member's BASE household, through the real
 * {@link EssentialsReadinessService} (Home's rule), not plans by author email.
 */
class GroupReadinessHouseholdTest {

    private static final String ORG = "org-1";
    private static final String HH = "hh-1";
    private static final String AUTHOR = "author@x.com";   // wrote every plan
    private static final String SPOUSE = "spouse@x.com";   // wrote none
    private static final String LONER = "loner@x.com";     // no household yet

    private GroupRepo groupRepo;
    private UserInfoRepo userInfoRepo;
    private MeetingPlaceRepo meetingPlaceRepo;
    private EvacuationPlanRepo evacuationPlanRepo;
    private EmergencyContactGroupRepo contactRepo;
    private MealPlanDataRepo mealRepo;
    private DemographicRepo demographicRepo;
    private GroupReadinessService service;

    @BeforeEach
    void setUp() {
        groupRepo = mock(GroupRepo.class);
        userInfoRepo = mock(UserInfoRepo.class);
        meetingPlaceRepo = mock(MeetingPlaceRepo.class);
        evacuationPlanRepo = mock(EvacuationPlanRepo.class);
        contactRepo = mock(EmergencyContactGroupRepo.class);
        mealRepo = mock(MealPlanDataRepo.class);
        demographicRepo = mock(DemographicRepo.class);
        EssentialsReadinessService essentials = new EssentialsReadinessService(meetingPlaceRepo,
                evacuationPlanRepo, contactRepo, mealRepo, demographicRepo, userInfoRepo);
        service = new GroupReadinessService(groupRepo, userInfoRepo,
                mock(HouseholdReadinessService.class), essentials);

        Group org = new Group();
        org.setGroupId(ORG);
        org.setGroupName("Ward");
        org.setGroupType("Church");
        org.setMemberEmails(new ArrayList<>(List.of(AUTHOR, SPOUSE)));
        when(groupRepo.findByGroupIdWithMembers(ORG)).thenReturn(Optional.of(org));

        Group household = new Group();
        household.setGroupId(HH);
        household.setGroupType("Household");
        household.setOwnerEmail(AUTHOR);
        when(groupRepo.findAllById(anyList())).thenReturn(List.of(household));

        when(userInfoRepo.findByUserEmailIn(anyList())).thenReturn(List.of(
                user(AUTHOR, HH), user(SPOUSE, HH), user(LONER, null)));

        // Every plan lives on the household, authored by AUTHOR.
        when(meetingPlaceRepo.findByHouseholdId(HH)).thenReturn(List.of(new MeetingPlace()));
        when(evacuationPlanRepo.findByHouseholdId(HH)).thenReturn(List.of(new EvacuationPlan()));
        when(contactRepo.findByHouseholdId(HH)).thenReturn(List.of(new EmergencyContactGroup()));
        when(mealRepo.findFirstByHouseholdId(HH)).thenReturn(Optional.of(new MealPlanData()));
        Demographic d = new Demographic();
        d.setAdults(2);
        when(demographicRepo.findFirstByHouseholdIdOrderByIdDesc(HH)).thenReturn(Optional.of(d));
    }

    private static UserInfo user(String email, String base) {
        UserInfo u = new UserInfo();
        u.setUserEmail(email);
        u.setBaseHouseholdId(base);
        return u;
    }

    @Test
    void twoMemberHousehold_whereOneAuthoredThePlan_readsTwoOfTwo() {
        GroupReadinessSummaryDto dto = service.buildReadinessSummary(ORG);

        assertThat(dto.getTotalMembers()).isEqualTo(2);
        assertThat(dto.getFullyReadyMembers()).isEqualTo(2);
        assertThat(dto.getReadinessPercent()).isEqualTo(100);
        assertThat(dto.getMembersWithMealPlan()).isEqualTo(2);
        assertThat(dto.getEssentials()).extracting(GroupReadinessSummaryDto.EssentialCount::key)
                .containsExactly("demographics", "mealPlan", "evacuation", "contacts");
        assertThat(dto.getEssentials()).allSatisfy(e -> {
            assertThat(e.done()).isEqualTo(2);
            assertThat(e.total()).isEqualTo(2);
            assertThat(e.label()).isNotBlank();
        });
        // One household evaluation served both members (no per-email plan lookups).
        verify(mealRepo, times(1)).findFirstByHouseholdId(HH);
        verify(mealRepo, never()).findFirstByOwnerEmailIgnoreCase(anyString());
    }

    @Test
    void memberWithoutAHousehold_countsAsNothingDone() {
        Group org = new Group();
        org.setGroupId(ORG);
        org.setMemberEmails(new ArrayList<>(List.of(AUTHOR, LONER)));
        when(groupRepo.findByGroupIdWithMembers(ORG)).thenReturn(Optional.of(org));

        GroupReadinessSummaryDto dto = service.buildReadinessSummary(ORG);

        assertThat(dto.getFullyReadyMembers()).isEqualTo(1);
        assertThat(dto.getMembersWithoutHousehold()).isEqualTo(1);
        assertThat(dto.getReadinessPercent()).isEqualTo(50);
        assertThat(dto.getEssentials().get(0).done()).isEqualTo(1);
    }

    @Test
    void emptyGroup_hasNoPercent_notZero() {
        Group org = new Group();
        org.setGroupId(ORG);
        org.setMemberEmails(new ArrayList<>());
        when(groupRepo.findByGroupIdWithMembers(ORG)).thenReturn(Optional.of(org));

        GroupReadinessSummaryDto dto = service.buildReadinessSummary(ORG);

        assertThat(dto.getReadinessPercent()).isNull();
        assertThat(dto.getEssentials()).hasSize(4);
        verifyNoInteractions(mealRepo);
        verify(groupRepo, never()).findAllById(anyCollection());
    }
}
