package io.sitprep.sitprepapi.gamification;

import io.sitprep.sitprepapi.domain.DrillCompletion;
import io.sitprep.sitprepapi.domain.EmergencyContact;
import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.domain.EvacuationPlan;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.dto.GoBagDtos.GoBagSummaryDto;
import io.sitprep.sitprepapi.dto.HomeStockpileDtos.HomeStockpileDto;
import io.sitprep.sitprepapi.repo.EmergencyContactGroupRepo;
import io.sitprep.sitprepapi.repo.EvacuationPlanRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import io.sitprep.sitprepapi.service.GoBagService;
import io.sitprep.sitprepapi.service.HomeStockpileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** T3: household criteria read the household's own records, on both sides of each threshold. */
class HouseholdTokenCriteriaTest {

    private static final String HH = "hh-1";

    private MeetingPlaceRepo meetingPlaces;
    private EmergencyContactGroupRepo contactGroups;
    private EvacuationPlanRepo evacuationPlans;
    private GroupRepo groups;
    private GoBagService goBags;
    private HomeStockpileService stockpile;
    private HouseholdTokenCriteria criteria;

    @BeforeEach
    void setUp() {
        meetingPlaces = mock(MeetingPlaceRepo.class);
        contactGroups = mock(EmergencyContactGroupRepo.class);
        evacuationPlans = mock(EvacuationPlanRepo.class);
        groups = mock(GroupRepo.class);
        goBags = mock(GoBagService.class);
        stockpile = mock(HomeStockpileService.class);
        criteria = new HouseholdTokenCriteria(meetingPlaces, contactGroups, evacuationPlans, groups, goBags, stockpile);
    }

    private void household(int places, int contacts, boolean route, List<String> drillKeys, int bagPct, int stockPct) {
        List<MeetingPlace> mp = new ArrayList<>();
        for (int i = 0; i < places; i++) mp.add(new MeetingPlace());
        when(meetingPlaces.findByHouseholdId(HH)).thenReturn(mp);

        EmergencyContactGroup g = new EmergencyContactGroup();
        List<EmergencyContact> cs = new ArrayList<>();
        for (int i = 0; i < contacts; i++) cs.add(new EmergencyContact());
        g.setContacts(cs);
        when(contactGroups.findByHouseholdId(HH)).thenReturn(List.of(g));

        EvacuationPlan plan = new EvacuationPlan();
        plan.setPrimaryRouteNotes(route ? "North on Main to I-15" : "  ");
        when(evacuationPlans.findByHouseholdId(HH)).thenReturn(List.of(plan));

        Group house = new Group();
        Map<String, DrillCompletion> log = new HashMap<>();
        drillKeys.forEach(k -> log.put(k, new DrillCompletion(Instant.now(), "ana@x.com")));
        house.setDrillLog(log);
        when(groups.findById(HH)).thenReturn(Optional.of(house));

        when(goBags.summariesForHousehold(HH)).thenReturn(List.of(
                new GoBagSummaryDto("b1", "Car", "car", null, 0, 10, bagPct, 0)));
        when(stockpile.getForHousehold(HH)).thenReturn(new HomeStockpileDto(
                null, null, null, null, 14, 2, stockPct, false, List.of(), Instant.now(), null, false, null));
    }

    @Test
    void aHouseholdThatHasDoneEverythingMeetsEveryRecordToken() {
        household(1, 2, true, List.of("go-bag", "go-bag#papers", "meeting-walk", "contact-test"), 80, 50);
        assertThat(criteria.met(HH)).containsExactlyInAnyOrder(
                TokenKey.MEETING_POINT, TokenKey.CONTACT_CIRCLE, TokenKey.PLAN_ARCHITECT,
                TokenKey.DRILL_CREW, TokenKey.PRACTICE_CADENCE, TokenKey.STOCKPILE_STEWARD);
    }

    @Test
    void oneShortOfEachThresholdMeetsNothingItShouldNot() {
        // 1 contact, a blank route, 2 distinct drills (the # step is the same drill), 79 % bag, 49 % stockpile.
        household(1, 1, false, List.of("go-bag", "go-bag#papers", "meeting-walk"), 79, 49);
        assertThat(criteria.met(HH)).containsExactlyInAnyOrder(TokenKey.MEETING_POINT, TokenKey.DRILL_CREW);
    }

    @Test
    void aFailingSourceDropsOnlyItsOwnToken() {
        household(1, 2, true, List.of("go-bag"), 100, 100);
        when(stockpile.getForHousehold(HH)).thenThrow(new RuntimeException("food planner down"));
        assertThat(criteria.met(HH))
                .contains(TokenKey.PLAN_ARCHITECT, TokenKey.DRILL_CREW)
                .doesNotContain(TokenKey.STOCKPILE_STEWARD);
    }
}
