package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.domain.HomeStockpileItem;
import io.sitprep.sitprepapi.dto.FoodPlanDtos.DemographicSummaryDto;
import io.sitprep.sitprepapi.dto.FoodPlanDtos.FoodPlanItemDto;
import io.sitprep.sitprepapi.dto.FoodPlanDtos.FoodPlanRecommendationDto;
import io.sitprep.sitprepapi.dto.FoodPlanDtos.MetaDto;
import io.sitprep.sitprepapi.dto.FoodPlanDtos.PlanDurationDto;
import io.sitprep.sitprepapi.dto.HomeStockpileDtos.HomeStockpileDto;
import io.sitprep.sitprepapi.dto.HomeStockpileDtos.StockpileCategoryDto;
import io.sitprep.sitprepapi.dto.HomeStockpileDtos.StockpileItemDto;
import io.sitprep.sitprepapi.repo.DemographicRepo;
import io.sitprep.sitprepapi.repo.HomeStockpileItemRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 14-Day At-Home Stockpile guards: the ADVANCED framing, Food-&-Water status
 * DERIVED from {@link FoodPlanCalculatorService}, demographic scaling, the
 * {@code stockpile-*} keyspace that decouples the stockpile from the Go Bag, and
 * the persistent per-household check-off (overlay, item-level percent, toggle,
 * and the key whitelist that keeps Food & Water + go-bag keys out of the table).
 */
@ExtendWith(MockitoExtension.class)
class HomeStockpileServiceTest {

    private static final String HH = "hh-1";

    @Mock DemographicRepo demographicRepo;
    @Mock FoodPlanCalculatorService foodPlanCalculatorService;
    @Mock HomeStockpileItemRepo stockpileItemRepo;
    @Mock io.sitprep.sitprepapi.websocket.WebSocketMessageSender ws;
    @Mock SupplyProductCatalog supplyCatalog;
    @Mock CommerceSuppressionService commerceSuppression;

    private HomeStockpileService service() {
        // supplyCatalog.byKey(...) returns Optional.empty() by Mockito default and
        // commerceSuppression.suppressionReason(...) returns null, so items ship
        // with no links and not-suppressed — the assertions below are unaffected.
        return new HomeStockpileService(demographicRepo, foodPlanCalculatorService,
                stockpileItemRepo, ws, supplyCatalog, commerceSuppression);
    }

    private static Demographic demo(int adults, int teens, int kids, int infants,
                                    int dogs, int cats, int pets) {
        Demographic d = new Demographic();
        d.setHouseholdId(HH);
        d.setAdults(adults);
        d.setTeens(teens);
        d.setKids(kids);
        d.setInfants(infants);
        d.setDogs(dogs);
        d.setCats(cats);
        d.setPets(pets);
        return d;
    }

    private static HomeStockpileItem row(String itemKey) {
        HomeStockpileItem r = new HomeStockpileItem();
        r.setHouseholdId(HH);
        r.setItemKey(itemKey);
        return r;
    }

    private static FoodPlanItemDto foodItem(String name, int priority) {
        return new FoodPlanItemDto(name, "unit", 1.0, 1, 1.0, "pkg", "pkg", priority, List.of());
    }

    private static FoodPlanRecommendationDto plan(int planDays, FoodPlanItemDto... items) {
        return new FoodPlanRecommendationDto(
                new DemographicSummaryDto(0, 0, 0, 0, 0, 0, 0, 0),
                planDays,
                new PlanDurationDto(planDays, "Days"),
                List.of(items),
                new MetaDto(Instant.EPOCH, "test"));
    }

    private static StockpileCategoryDto cat(HomeStockpileDto dto, String key) {
        return dto.categories().stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
    }

    private static StockpileItemDto item(HomeStockpileDto dto, String itemKey) {
        return dto.categories().stream().flatMap(c -> c.items().stream())
                .filter(i -> i.itemKey().equals(itemKey)).findFirst().orElse(null);
    }

    // ── ADVANCED framing + baseline fallback ────────────────────────────────

    @Test
    void noDemographic_returnsBaselineKit_foodWaterUnmet_withCta() {
        when(demographicRepo.findFirstByHouseholdIdOrderByIdDesc(HH)).thenReturn(Optional.empty());
        // No food-plan lookup happens when there's no demographic (short-circuit).
        // findByHouseholdId returns Mockito's default empty list → nothing checked.

        HomeStockpileDto dto = service().getForHousehold(HH);

        assertThat(dto.tier()).isEqualTo("ADVANCED");
        assertThat(dto.context()).isEqualTo("stay_home_14_day");
        assertThat(dto.planDays()).isEqualTo(14);
        assertThat(dto.persons()).isEqualTo(1);           // never scales to 0
        assertThat(dto.foodWaterSatisfied()).isFalse();
        assertThat(dto.percentComplete()).isZero();       // nothing satisfied

        StockpileCategoryDto foodWater = cat(dto, "food_water");
        assertThat(foodWater.satisfied()).isFalse();
        assertThat(foodWater.cta()).isEqualTo("Build 14-day food plan");
        assertThat(foodWater.route()).isEqualTo("/create-foodsupply");
    }

    @Test
    void categories_areExactlyTheFiveExpected_andAllTrackable() {
        when(demographicRepo.findFirstByHouseholdIdOrderByIdDesc(HH)).thenReturn(Optional.empty());

        HomeStockpileDto dto = service().getForHousehold(HH);

        assertThat(dto.categories()).extracting(StockpileCategoryDto::key)
                .containsExactly("food_water", "sanitation", "power_heat", "medical", "tools_safety");
        // v2: every category counts toward completion (food_water derived, non-food persisted).
        assertThat(dto.categories()).allMatch(StockpileCategoryDto::trackable);
    }

    // ── Food & Water status DERIVED from the Food Planner ───────────────────

    @Test
    void realFourteenDayFoodPlan_marksFoodWaterSatisfied_partialOverall() {
        when(demographicRepo.findFirstByHouseholdIdOrderByIdDesc(HH))
                .thenReturn(Optional.of(demo(2, 0, 0, 0, 0, 0, 0)));
        when(foodPlanCalculatorService.recommendForHousehold(HH))
                .thenReturn(plan(14, foodItem("Water", 1), foodItem("Cereal", 99)));

        HomeStockpileDto dto = service().getForHousehold(HH);

        assertThat(dto.foodWaterSatisfied()).isTrue();
        StockpileCategoryDto foodWater = cat(dto, "food_water");
        assertThat(foodWater.satisfied()).isTrue();
        assertThat(foodWater.cta()).isNull();
        assertThat(foodWater.route()).isNull();
        assertThat(foodWater.items()).allMatch(StockpileItemDto::satisfied);
        // Food & Water done but every non-food item still unchecked → partial, not 100.
        assertThat(dto.percentComplete()).isPositive().isLessThan(100);
    }

    @Test
    void foodPlanUnderFourteenDays_isNotSatisfied() {
        when(demographicRepo.findFirstByHouseholdIdOrderByIdDesc(HH))
                .thenReturn(Optional.of(demo(2, 0, 0, 0, 0, 0, 0)));
        when(foodPlanCalculatorService.recommendForHousehold(HH))
                .thenReturn(plan(3, foodItem("Water", 1), foodItem("Cereal", 99)));

        HomeStockpileDto dto = service().getForHousehold(HH);

        assertThat(dto.foodWaterSatisfied()).isFalse();
        assertThat(cat(dto, "food_water").route()).isEqualTo("/create-foodsupply");
    }

    @Test
    void fourteenDaysButWaterAndPetOnly_isNotSatisfied() {
        // 14-day duration but no real human menu — only the always-present
        // Water + pet-food lines. Must NOT count as a completed food plan.
        when(demographicRepo.findFirstByHouseholdIdOrderByIdDesc(HH))
                .thenReturn(Optional.of(demo(2, 0, 0, 0, 1, 0, 0)));
        when(foodPlanCalculatorService.recommendForHousehold(HH))
                .thenReturn(plan(14, foodItem("Water", 1), foodItem("Worth of dog food", 99)));

        HomeStockpileDto dto = service().getForHousehold(HH);

        assertThat(dto.foodWaterSatisfied()).isFalse();
    }

    // ── Demographic scaling ─────────────────────────────────────────────────

    @Test
    void demographicScaling_scalesSanitation_andAddsInfantAndPetLines() {
        when(demographicRepo.findFirstByHouseholdIdOrderByIdDesc(HH))
                .thenReturn(Optional.of(demo(2, 0, 0, 1, 1, 0, 0))); // 3 persons, 1 infant, 1 dog
        when(foodPlanCalculatorService.recommendForHousehold(HH))
                .thenReturn(plan(3)); // no real plan

        HomeStockpileDto dto = service().getForHousehold(HH);

        assertThat(dto.persons()).isEqualTo(3);
        assertThat(item(dto, "stockpile-toilet-paper").qtyRecommended()).isEqualTo(6); // persons * 2
        // Infant-only and pet-only lines appear because of the composition.
        assertThat(item(dto, "stockpile-diapers")).isNotNull();
        assertThat(item(dto, "stockpile-diapers").qtyRecommended()).isEqualTo(84); // infants * 84
        assertThat(item(dto, "stockpile-pet-sanitation")).isNotNull();
    }

    @Test
    void noInfantsNoPets_omitsThoseLines() {
        when(demographicRepo.findFirstByHouseholdIdOrderByIdDesc(HH))
                .thenReturn(Optional.of(demo(1, 0, 0, 0, 0, 0, 0)));
        when(foodPlanCalculatorService.recommendForHousehold(HH)).thenReturn(plan(3));

        HomeStockpileDto dto = service().getForHousehold(HH);

        assertThat(item(dto, "stockpile-diapers")).isNull();
        assertThat(item(dto, "stockpile-pet-sanitation")).isNull();
    }

    // ── Persistent check-off: overlay + item-level percent ──────────────────

    @Test
    void checkedRows_markNonFoodItemsSatisfied_andRaisePercent() {
        when(demographicRepo.findFirstByHouseholdIdOrderByIdDesc(HH))
                .thenReturn(Optional.of(demo(1, 0, 0, 0, 0, 0, 0)));
        when(foodPlanCalculatorService.recommendForHousehold(HH)).thenReturn(plan(3)); // food/water unmet
        when(stockpileItemRepo.findByHouseholdId(HH))
                .thenReturn(List.of(row("stockpile-toilet-paper"), row("stockpile-flashlights")));

        HomeStockpileDto dto = service().getForHousehold(HH);

        assertThat(item(dto, "stockpile-toilet-paper").satisfied()).isTrue();
        assertThat(item(dto, "stockpile-flashlights").satisfied()).isTrue();
        assertThat(item(dto, "stockpile-bleach").satisfied()).isFalse();
        assertThat(dto.foodWaterSatisfied()).isFalse();
        // Two checked items → percent is positive (food & water contributes nothing here).
        assertThat(dto.percentComplete()).isPositive();
        // Sanitation isn't fully satisfied (only 1 of its items checked).
        assertThat(cat(dto, "sanitation").satisfied()).isFalse();
    }

    // ── Toggle endpoint semantics ───────────────────────────────────────────

    @Test
    void toggle_insertsWhenAbsent_viaIdempotentUpsert_andReturnsUpdatedDto() {
        when(stockpileItemRepo.existsByHouseholdIdAndItemKey(HH, "stockpile-toilet-paper")).thenReturn(false);
        when(demographicRepo.findFirstByHouseholdIdOrderByIdDesc(HH))
                .thenReturn(Optional.of(demo(1, 0, 0, 0, 0, 0, 0)));
        when(foodPlanCalculatorService.recommendForHousehold(HH)).thenReturn(plan(3));
        when(stockpileItemRepo.findByHouseholdId(HH)).thenReturn(List.of(row("stockpile-toilet-paper")));

        HomeStockpileDto dto = service().toggleItem(HH, "stockpile-toilet-paper");

        // Insert goes through the idempotent ON CONFLICT DO NOTHING upsert, never a
        // save()/merge that would throw (and poison the tx) on a concurrent
        // duplicate check by another member — see SYSTEM_TRAPS T-16.
        verify(stockpileItemRepo).insertIfAbsent(HH, "stockpile-toilet-paper");
        verify(stockpileItemRepo, never()).deleteByHouseholdIdAndItemKey(any(), any());
        assertThat(item(dto, "stockpile-toilet-paper").satisfied()).isTrue();
    }

    @Test
    void toggle_deletesWhenPresent() {
        when(stockpileItemRepo.existsByHouseholdIdAndItemKey(HH, "stockpile-flashlights")).thenReturn(true);
        when(demographicRepo.findFirstByHouseholdIdOrderByIdDesc(HH))
                .thenReturn(Optional.of(demo(1, 0, 0, 0, 0, 0, 0)));
        when(foodPlanCalculatorService.recommendForHousehold(HH)).thenReturn(plan(3));
        // After delete the row is gone → empty (Mockito default).

        HomeStockpileDto dto = service().toggleItem(HH, "stockpile-flashlights");

        verify(stockpileItemRepo).deleteByHouseholdIdAndItemKey(HH, "stockpile-flashlights");
        verify(stockpileItemRepo, never()).insertIfAbsent(any(), any());
        assertThat(item(dto, "stockpile-flashlights").satisfied()).isFalse();
    }

    // ── Whitelist: only checkable non-food keys may be written ──────────────

    @Test
    void toggle_rejectsFoodWaterKey_becauseItIsDerived() {
        assertThatThrownBy(() -> service().toggleItem(HH, "stockpile-water-14d"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("checkable");
        verifyNoInteractions(stockpileItemRepo);
    }

    @Test
    void toggle_rejectsGoBagKey_preservingDecoupling() {
        // A go-bag itemKey must never be writable into the stockpile table.
        assertThatThrownBy(() -> service().toggleItem(HH, "noaa-radio"))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(stockpileItemRepo);
    }

    @Test
    void toggle_rejectsUnknownKey() {
        assertThatThrownBy(() -> service().toggleItem(HH, "totally-made-up"))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(stockpileItemRepo);
    }

    // ── Drift guard: the whitelist == the emitted non-food catalog ──────────

    @Test
    void checkableItemKeys_matchTheEmittedNonFoodKeys() {
        when(demographicRepo.findFirstByHouseholdIdOrderByIdDesc(HH))
                .thenReturn(Optional.of(demo(2, 1, 1, 1, 1, 1, 1))); // maximal: infants + pets present
        when(foodPlanCalculatorService.recommendForHousehold(HH)).thenReturn(plan(3));

        HomeStockpileDto dto = service().getForHousehold(HH);

        Set<String> emittedNonFood = dto.categories().stream()
                .filter(c -> !"food_water".equals(c.key()))
                .flatMap(c -> c.items().stream())
                .map(StockpileItemDto::itemKey)
                .collect(Collectors.toSet());
        assertThat(HomeStockpileService.checkableItemKeys()).isEqualTo(emittedNonFood);
    }

    // ── Decoupling guard: every item is namespaced away from the Go Bag ─────

    @Test
    void everyItemKey_isStockpileNamespaced_soItNeverFulfilsAGoBagItem() {
        when(demographicRepo.findFirstByHouseholdIdOrderByIdDesc(HH))
                .thenReturn(Optional.of(demo(2, 1, 1, 1, 1, 1, 1)));
        when(foodPlanCalculatorService.recommendForHousehold(HH)).thenReturn(plan(3));

        HomeStockpileDto dto = service().getForHousehold(HH);

        Set<String> stockpileKeys = dto.categories().stream()
                .flatMap(c -> c.items().stream())
                .map(StockpileItemDto::itemKey)
                .collect(Collectors.toSet());
        // The ACTUAL go-bag keyset — not a hardcoded sample — so a collision
        // introduced from EITHER catalog fails this guard (see T-15).
        Set<String> goBagKeys = GoBagRecommendationService.templateItemKeys();

        assertThat(stockpileKeys).isNotEmpty();
        assertThat(goBagKeys).isNotEmpty();
        // (1) Stockpile side: every key is namespaced.
        assertThat(stockpileKeys).allMatch(k -> k.startsWith("stockpile-"));
        // (2) Go-bag side: no go-bag key ever intrudes into the stockpile namespace.
        assertThat(goBagKeys).noneMatch(k -> k.startsWith("stockpile-"));
        // (3) The two real keysets are disjoint — no cross-fulfilment possible.
        assertThat(stockpileKeys).doesNotContainAnyElementsOf(goBagKeys);
        // Concept overlap is expected (radio, first-aid) — same idea, distinct keys.
        assertThat(stockpileKeys).contains("stockpile-noaa-radio", "stockpile-first-aid-kit");
        // Every checkable key is exactly the emitted non-food set (also guarded above).
        assertThat(HomeStockpileService.checkableItemKeys()).allMatch(stockpileKeys::contains);
    }
}
