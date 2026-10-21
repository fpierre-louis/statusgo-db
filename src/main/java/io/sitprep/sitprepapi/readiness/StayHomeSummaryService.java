package io.sitprep.sitprepapi.readiness;

import io.sitprep.sitprepapi.domain.MealPlanData;
import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.dto.HomeStockpileDtos.HomeStockpileDto;
import io.sitprep.sitprepapi.dto.HomeStockpileDtos.StockpileCategoryDto;
import io.sitprep.sitprepapi.dto.PlanActivationDtos.StayHomeSummaryDto;
import io.sitprep.sitprepapi.repo.MealPlanDataRepo;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import io.sitprep.sitprepapi.service.HomeStockpileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * What a household has where it is: the stay-home half of a live plan
 * (EXEC-11 Phase 3, owner ruling 2026-10-21).
 *
 * <p><b>Live-resolved, never snapshotted.</b> A live plan pins its MOVEMENT
 * (where to meet, where to go; V116/V117), because a saved edit must not
 * silently change an instruction people are following. Readiness is the
 * opposite: if someone fills the water jugs or names the safe room while the
 * household is sheltering, the plan should say so at once. So this is read at
 * request time from the same records the Household SitPrep Plan reads.
 *
 * <p><b>Household audience only.</b> It is attached to the authorized detail
 * DTO, never to the public link projection: a safe room's name and a
 * household's supplies are not shared with link holders.
 *
 * <p>Best-effort: any part that cannot be read is left empty rather than
 * failing the plan read.
 */
@Service
public class StayHomeSummaryService {

    private static final Logger log = LoggerFactory.getLogger(StayHomeSummaryService.class);

    private final MeetingPlaceRepo meetingPlaceRepo;
    private final MealPlanDataRepo mealPlanDataRepo;
    private final HomeStockpileService homeStockpileService;

    public StayHomeSummaryService(MeetingPlaceRepo meetingPlaceRepo,
                                  MealPlanDataRepo mealPlanDataRepo,
                                  HomeStockpileService homeStockpileService) {
        this.meetingPlaceRepo = meetingPlaceRepo;
        this.mealPlanDataRepo = mealPlanDataRepo;
        this.homeStockpileService = homeStockpileService;
    }

    /** The household's stay-home facts, or null without a household. */
    public StayHomeSummaryDto summaryFor(String householdId) {
        if (householdId == null || householdId.isBlank()) return null;

        String roomName = null;
        String roomAddress = null;
        try {
            MeetingPlace room = meetingPlaceRepo.findByHouseholdId(householdId).stream()
                    .filter(EssentialsReadinessService::isSafeRoom)
                    .findFirst().orElse(null);
            if (room != null) {
                roomName = blankToNull(room.getName());
                roomAddress = blankToNull(room.getAddress());
                if (roomName == null) roomName = roomAddress;
            }
        } catch (Exception e) {
            log.warn("stay-home summary: safe room lookup failed for {}: {}", householdId, e.getMessage());
        }

        boolean foodPlanned = false;
        Integer foodDays = null;
        String foodUnit = null;
        boolean gathered = false;
        try {
            MealPlanData meal = mealPlanDataRepo.findFirstByHouseholdId(householdId).orElse(null);
            if (meal != null) {
                int qty = meal.getPlanDuration() == null ? 0 : meal.getPlanDuration().getQuantity();
                foodDays = qty > 0 ? qty : null;
                foodUnit = foodDays == null ? null
                        : blankToNull(meal.getPlanDuration().getUnit()) == null ? "days" : meal.getPlanDuration().getUnit();
                foodPlanned = foodDays != null || (meal.getMealPlan() != null && !meal.getMealPlan().isEmpty());
                gathered = Boolean.TRUE.equals(meal.getSuppliesGathered());
            }
        } catch (Exception e) {
            log.warn("stay-home summary: food plan lookup failed for {}: {}", householdId, e.getMessage());
        }

        Integer onHand = null;
        Integer total = null;
        try {
            HomeStockpileDto kit = homeStockpileService.getForHousehold(householdId);
            if (kit != null && kit.categories() != null) {
                List<StockpileCategoryDto> cats = kit.categories();
                // The same count the saved plan shows: every item, on hand or not.
                total = cats.stream().filter(Objects::nonNull)
                        .mapToInt(c -> c.items() == null ? 0 : c.items().size()).sum();
                onHand = (int) cats.stream().filter(Objects::nonNull)
                        .flatMap(c -> c.items() == null ? java.util.stream.Stream.empty() : c.items().stream())
                        .filter(i -> i != null && i.satisfied()).count();
                if (total == 0) { total = null; onHand = null; }
            }
        } catch (Exception e) {
            log.warn("stay-home summary: home supplies lookup failed for {}: {}", householdId, e.getMessage());
        }

        return new StayHomeSummaryDto(roomName, roomAddress, foodPlanned, foodDays, foodUnit, gathered, onHand, total);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
