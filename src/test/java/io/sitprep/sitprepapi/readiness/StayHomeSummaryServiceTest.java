package io.sitprep.sitprepapi.readiness;

import io.sitprep.sitprepapi.domain.MealPlanData;
import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.domain.MeetingPlaceTier;
import io.sitprep.sitprepapi.domain.PlanDuration;
import io.sitprep.sitprepapi.dto.HomeStockpileDtos.HomeStockpileDto;
import io.sitprep.sitprepapi.dto.HomeStockpileDtos.StockpileCategoryDto;
import io.sitprep.sitprepapi.dto.HomeStockpileDtos.StockpileItemDto;
import io.sitprep.sitprepapi.dto.PlanActivationDtos.StayHomeSummaryDto;
import io.sitprep.sitprepapi.repo.MealPlanDataRepo;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import io.sitprep.sitprepapi.service.HomeStockpileService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** EXEC-11 Phase 3: the live stay-home summary on a household's active plan. */
class StayHomeSummaryServiceTest {

    private static final String HH = "hh-1";

    private final MeetingPlaceRepo places = mock(MeetingPlaceRepo.class);
    private final MealPlanDataRepo meals = mock(MealPlanDataRepo.class);
    private final HomeStockpileService kit = mock(HomeStockpileService.class);
    private final StayHomeSummaryService service = new StayHomeSummaryService(places, meals, kit);

    // Records are final, so they are built, not mocked.
    private static StockpileItemDto item(boolean satisfied) {
        return new StockpileItemDto("stockpile-x", "Item", 1, "ea", 0, null, satisfied, null, null, null);
    }

    @Test
    void readsTheSafeRoomFoodAndSuppliesLive() {
        MeetingPlace park = new MeetingPlace();
        park.setName("Park");
        MeetingPlace room = new MeetingPlace();
        room.setMeetingTier(MeetingPlaceTier.INDOOR_SAFE_ROOM);
        room.setName("Basement storage room");
        when(places.findByHouseholdId(HH)).thenReturn(List.of(park, room));

        MealPlanData meal = new MealPlanData();
        PlanDuration d = new PlanDuration();
        d.setQuantity(3);
        d.setUnit("days");
        meal.setPlanDuration(d);
        meal.setSuppliesGathered(true);
        when(meals.findFirstByHouseholdId(HH)).thenReturn(Optional.of(meal));

        StockpileCategoryDto cat = new StockpileCategoryDto("sanitation", "Sanitation", null, true, false, null, null,
                List.of(item(true), item(true), item(false)));
        HomeStockpileDto dto = new HomeStockpileDto("stay_home_14_day", "ADVANCED", "Home kit", null, 14, 4, 66, true,
                List.of(cat), java.time.Instant.now(), "v1", false, null);
        when(kit.getForHousehold(HH)).thenReturn(dto);

        StayHomeSummaryDto s = service.summaryFor(HH);
        assertThat(s.safeRoomName()).isEqualTo("Basement storage room");
        assertThat(s.foodPlanned()).isTrue();
        assertThat(s.foodDays()).isEqualTo(3);
        assertThat(s.foodUnit()).isEqualTo("days");
        assertThat(s.foodSuppliesGathered()).isTrue();
        assertThat(s.suppliesOnHand()).isEqualTo(2);
        assertThat(s.suppliesTotal()).isEqualTo(3);
    }

    @Test
    void emptyPartsStayEmptyAndAFailureNeverSinksTheRead() {
        when(places.findByHouseholdId(HH)).thenReturn(List.of(new MeetingPlace()));
        when(meals.findFirstByHouseholdId(HH)).thenReturn(Optional.empty());
        when(kit.getForHousehold(HH)).thenThrow(new RuntimeException("boom"));

        StayHomeSummaryDto s = service.summaryFor(HH);
        assertThat(s.safeRoomName()).isNull();
        assertThat(s.foodPlanned()).isFalse();
        assertThat(s.foodDays()).isNull();
        assertThat(s.suppliesTotal()).isNull();
    }

    @Test
    void noHouseholdNoSummary() {
        assertThat(service.summaryFor(null)).isNull();
        assertThat(service.summaryFor(" ")).isNull();
    }
}
