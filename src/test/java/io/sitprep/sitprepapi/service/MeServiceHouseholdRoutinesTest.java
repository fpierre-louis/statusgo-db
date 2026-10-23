package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.HouseholdPlanDto;
import io.sitprep.sitprepapi.dto.HouseholdRoutineDtos.HouseholdRoutineDto;
import io.sitprep.sitprepapi.dto.HouseholdRoutineDtos.SlotValueDto;
import io.sitprep.sitprepapi.repo.*;
import io.sitprep.sitprepapi.routine.HouseholdRoutineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** EXEC-R1: saved household routines ride the plan document, and the paper is outdated once one changes. */
class MeServiceHouseholdRoutinesTest {

    private static final String HH = "hh-1";

    private final GroupRepo groupRepo = mock(GroupRepo.class);
    private final MeSubfetchService subfetch = mock(MeSubfetchService.class);
    private final HouseholdRoutineService routines = mock(HouseholdRoutineService.class);
    private Group household;

    private MeService service() {
        MeService s = new MeService(
                mock(UserInfoRepo.class), groupRepo, mock(DemographicRepo.class), mock(MealPlanDataRepo.class),
                mock(EvacuationPlanRepo.class), mock(MeetingPlaceRepo.class), mock(OriginLocationRepo.class),
                mock(EmergencyContactGroupRepo.class), mock(PlanActivationRepo.class), mock(PostRepo.class),
                mock(GroupReadStateRepo.class), mock(GroupPostRepo.class), mock(GroupMutePrefRepo.class),
                mock(HouseholdRitualRepo.class), subfetch, mock(UserInfoService.class), mock(PlatformAccessService.class),
                mock(GoBagService.class), mock(EmergencySupportService.class), mock(StandingConditionService.class),
                mock(HouseholdReadinessService.class), new ObjectMapper(), mock(AgencyStaffService.class));
        s.setRoutineService(routines);
        return s;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        household = new Group();
        household.setGroupId(HH);
        household.setGroupType("Household");
        household.setGroupName("The Pierre-Louis home");
        when(groupRepo.findByGroupId(HH)).thenReturn(Optional.of(household));
        when(subfetch.runReadOnly(any())).thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(0)).get());
    }

    private static HouseholdRoutineDto routine(String key, String hazard, String title, String thenText) {
        return new HouseholdRoutineDto(key, hazard, title, "If " + title, thenText,
                List.of(new SlotValueDto("who", "Sam", "user:u-3")), 1, Instant.parse("2026-10-23T12:00:00Z"));
    }

    @Test
    void thePlanDocumentCarriesTheSavedRoutinesInServiceOrder() {
        when(routines.listForPlan(HH)).thenReturn(List.of(
                routine("fire-exit-routine", "home-fire", "Smoke alarm exit", "We meet at the mailbox."),
                routine("tornado-night-routine", "tornado", "Tornado at night", "Sam wakes everyone.")));
        HouseholdPlanDto doc = service().buildHouseholdPlanDocument(HH);
        assertThat(doc.routines()).extracting(HouseholdRoutineDto::routineKey)
                .containsExactly("fire-exit-routine", "tornado-night-routine");
        assertThat(doc.routines().get(1).slots()).singleElement().satisfies(s -> assertThat(s.value()).isEqualTo("Sam"));
        assertThat(doc.householdId()).isEqualTo(HH);
    }

    @Test
    void aHouseholdWithNoRoutinesGetsAnEmptyListNotNull() {
        when(routines.listForPlan(HH)).thenReturn(List.of());
        assertThat(service().buildHouseholdPlanDocument(HH).routines()).isEmpty();
    }

    @Test
    void routinesOfferedSaysWhetherPracticeCanOfferOneAndSurvivesThePrintCopy() {
        when(routines.listForPlan(HH)).thenReturn(List.of());
        when(routines.routinesOffered()).thenReturn(false);
        assertThat(service().buildHouseholdPlanDocument(HH).routinesOffered()).isFalse();
        when(routines.routinesOffered()).thenReturn(true);
        MeService s = service();
        assertThat(s.buildHouseholdPlanDocument(HH).routinesOffered()).isTrue();
        assertThat(s.markHouseholdPlanPrinted(HH).routinesOffered()).isTrue();
    }

    @Test
    void anOfferCheckFailureSaysNotOffered() {
        when(routines.listForPlan(HH)).thenReturn(List.of());
        when(routines.routinesOffered()).thenThrow(new IllegalStateException("catalog down"));
        assertThat(service().buildHouseholdPlanDocument(HH).routinesOffered()).isFalse();
    }

    @Test
    void aRoutinesFailureDegradesOnlyThatSection() {
        when(routines.listForPlan(HH)).thenThrow(new IllegalStateException("table missing"));
        HouseholdPlanDto doc = service().buildHouseholdPlanDocument(HH);
        assertThat(doc.routines()).isEmpty();
        assertThat(doc.name()).isEqualTo("The Pierre-Louis home");
    }

    @Test
    void thePrintedPaperIsOutdatedAfterARoutineIsSavedAndCurrentAfterReprinting() {
        MeService service = service();
        when(routines.listForPlan(HH)).thenReturn(List.of());
        HouseholdPlanDto printed = service.markHouseholdPlanPrinted(HH);
        assertThat(printed.printedPlanOutdated()).isFalse();

        when(routines.listForPlan(HH)).thenReturn(List.of(routine("tornado-night-routine", "tornado", "Tornado at night", "Sam wakes everyone.")));
        assertThat(service.buildHouseholdPlanDocument(HH).printedPlanOutdated()).isTrue();

        // Printing again stamps the new content as current.
        assertThat(service.markHouseholdPlanPrinted(HH).printedPlanOutdated()).isFalse();
        assertThat(service.buildHouseholdPlanDocument(HH).printedPlanOutdated()).isFalse();

        // Removing the routine makes the paper outdated again.
        when(routines.listForPlan(HH)).thenReturn(List.of());
        assertThat(service.buildHouseholdPlanDocument(HH).printedPlanOutdated()).isTrue();
    }
}
