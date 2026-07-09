package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.domain.HomeStockpileItem;
import io.sitprep.sitprepapi.dto.FoodPlanDtos.FoodPlanItemDto;
import io.sitprep.sitprepapi.dto.FoodPlanDtos.FoodPlanRecommendationDto;
import io.sitprep.sitprepapi.dto.HomeStockpileDtos.HomeStockpileDto;
import io.sitprep.sitprepapi.dto.HomeStockpileDtos.StockpileCategoryDto;
import io.sitprep.sitprepapi.dto.HomeStockpileDtos.StockpileItemDto;
import io.sitprep.sitprepapi.repo.DemographicRepo;
import io.sitprep.sitprepapi.repo.HomeStockpileItemRepo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Backend smart-calculator for the <b>14-Day At-Home Stockpile</b> — the
 * ADVANCED-tier, stay-at-home survival reserve (distinct from the 3-day Go Bag).
 *
 * <p>Reads the household {@link Demographic} and the existing
 * {@link FoodPlanCalculatorService} output, and returns a fully-computed,
 * demographic-scaled {@link HomeStockpileDto}. The React "Advanced Readiness"
 * surface renders it verbatim.</p>
 *
 * <p><b>By construction this never touches baseline readiness:</b> it is a
 * standalone advisory surface (like {@link RiskProfileService}) exposed on its
 * own endpoint and is deliberately NOT wired into {@code HouseholdReadinessService}'s
 * pillar scoring — so a bare stockpile can never lower {@code readinessPercent}.</p>
 *
 * <p><b>Food &amp; Water is delegated, not duplicated.</b> The one quantitative
 * hard part (serving sizes, package math, water gallons) already lives in
 * {@link FoodPlanCalculatorService}; here we only ASK it whether a real 14-day
 * plan exists and route users to {@code /create-foodsupply} when it doesn't.</p>
 */
@Service
public class HomeStockpileService {

    static final int PLAN_DAYS = 14;
    static final String CONTEXT = "stay_home_14_day";
    static final String TIER = "ADVANCED";
    static final String DATASET_VERSION = "home-stockpile-v2-2026.07";
    static final String FOOD_PLAN_ROUTE = "/create-foodsupply";

    /**
     * Every checkable NON-FOOD item key — the toggle whitelist. Food &amp; Water
     * keys ({@code stockpile-water-14d}, {@code stockpile-food-14d}) are
     * intentionally excluded: that category is DERIVED from the Food Planner, not
     * checkable. Go-bag keys are absent by construction (all here are
     * {@code stockpile-*}). A drift-guard test asserts this set equals the
     * non-food keys {@link #getForHousehold} emits for a maximal household.
     */
    static final Set<String> CHECKABLE_ITEM_KEYS = Set.of(
            // sanitation
            "stockpile-toilet-paper", "stockpile-hand-sanitizer", "stockpile-soap-detergent",
            "stockpile-garbage-bags", "stockpile-bleach", "stockpile-diapers",
            "stockpile-baby-wipes", "stockpile-pet-sanitation",
            // power_heat
            "stockpile-flashlights", "stockpile-batteries", "stockpile-manual-can-opener",
            "stockpile-lantern", "stockpile-power-station", "stockpile-warm-blankets",
            "stockpile-co-detector",
            // medical
            "stockpile-first-aid-kit", "stockpile-rx-14day", "stockpile-otc-meds",
            "stockpile-electrolytes", "stockpile-n95-masks", "stockpile-thermometer",
            // tools_safety
            "stockpile-fire-extinguisher", "stockpile-noaa-radio", "stockpile-utility-shutoff-wrench",
            "stockpile-multi-tool", "stockpile-duct-tape-sheeting", "stockpile-work-gloves",
            "stockpile-document-binder");

    /** Package-private accessor for the drift-guard test. */
    static Set<String> checkableItemKeys() {
        return CHECKABLE_ITEM_KEYS;
    }

    private final DemographicRepo demographicRepo;
    private final FoodPlanCalculatorService foodPlanCalculatorService;
    private final HomeStockpileItemRepo stockpileItemRepo;

    public HomeStockpileService(DemographicRepo demographicRepo,
                                FoodPlanCalculatorService foodPlanCalculatorService,
                                HomeStockpileItemRepo stockpileItemRepo) {
        this.demographicRepo = demographicRepo;
        this.foodPlanCalculatorService = foodPlanCalculatorService;
        this.stockpileItemRepo = stockpileItemRepo;
    }

    @Transactional(readOnly = true)
    public HomeStockpileDto getForHousehold(String householdId) {
        Demographic demo = demographicRepo
                .findFirstByHouseholdIdOrderByIdDesc(householdId)
                .orElse(null);

        int adults  = demo != null ? demo.getAdults()  : 0;
        int teens   = demo != null ? demo.getTeens()   : 0;
        int kids    = demo != null ? demo.getKids()    : 0;
        int infants = demo != null ? demo.getInfants() : 0;
        int pets    = demo != null ? (demo.getDogs() + demo.getCats() + demo.getPets()) : 0;

        // Never scale to zero — an un-onboarded household still sees a baseline 1-person kit.
        int persons = Math.max(1, adults + teens + kids + infants);
        int adultsTeens = Math.max(1, adults + teens);

        // Food & Water status is DERIVED from the Food Planner (single source of food math).
        boolean foodWaterSatisfied = demo != null && hasRealFourteenDayFoodPlan(householdId);

        // Which non-food items has this household already marked "on hand"?
        Set<String> checked = new HashSet<>();
        for (HomeStockpileItem row : stockpileItemRepo.findByHouseholdId(householdId)) {
            checked.add(row.getItemKey());
        }

        List<StockpileCategoryDto> categories = new ArrayList<>();
        categories.add(foodWaterCategory(foodWaterSatisfied));                          // derived
        categories.add(applyChecked(sanitationCategory(persons, infants, pets), checked));
        categories.add(applyChecked(powerHeatCategory(persons), checked));
        categories.add(applyChecked(medicalCategory(persons), checked));
        categories.add(applyChecked(toolsSafetyCategory(adultsTeens), checked));

        // Completion now spans EVERY item: Food & Water's two derived lines plus
        // each checked-off non-food item. The optimistic FE uses the same formula.
        List<StockpileItemDto> allItems = categories.stream()
                .flatMap(c -> c.items().stream())
                .toList();
        int total = allItems.size();
        long done = allItems.stream().filter(StockpileItemDto::satisfied).count();
        int percentComplete = total == 0 ? 0 : (int) Math.round(100.0 * done / total);

        return new HomeStockpileDto(
                CONTEXT,
                TIER,
                "14-Day Home Survival Kit",
                "A long-term, stay-at-home reserve for when help is delayed. "
                        + "Build it over time — it never lowers your baseline readiness.",
                PLAN_DAYS,
                persons,
                percentComplete,
                foodWaterSatisfied,
                categories,
                Instant.now(),
                DATASET_VERSION
        );
    }

    // ── Food & Water completeness (delegated to FoodPlanCalculatorService) ──────

    /**
     * True when the household has built a real 14-day food plan: the normalized
     * duration covers at least 14 days AND the computed list contains genuine
     * pantry food (not just the always-present water / infant / pet lines).
     */
    private boolean hasRealFourteenDayFoodPlan(String householdId) {
        FoodPlanRecommendationDto plan = foodPlanCalculatorService.recommendForHousehold(householdId);
        if (plan == null || plan.planDays() < PLAN_DAYS || plan.items() == null) {
            return false;
        }
        return plan.items().stream().anyMatch(HomeStockpileService::isPantryFood);
    }

    /**
     * A real human food item — excludes the inline lines that appear even with
     * no saved menu: Water (priority 1), Baby Formula (2), Baby Food (3), and
     * pet food. Any remaining priority-99 item proves a menu was actually built.
     */
    private static boolean isPantryFood(FoodPlanItemDto it) {
        if (it == null || it.priority() <= 3) return false;
        String n = it.item() == null ? "" : it.item().toLowerCase(Locale.ROOT);
        return !(n.contains("dog food") || n.contains("cat food")
                || n.contains("pet food") || n.contains("formula") || n.contains("baby"));
    }

    // ── Toggle persistence (mark on-hand / clear) ──────────────────────────────

    /**
     * Toggles a non-food stockpile item's "on hand" state for a household and
     * returns the freshly recomputed stockpile. Insert = checked, delete =
     * unchecked. Rejects any key that is not a checkable non-food catalog item,
     * so Food &amp; Water keys (derived) and go-bag keys can never be written here.
     */
    @Transactional
    public HomeStockpileDto toggleItem(String householdId, String itemKey) {
        if (itemKey == null || !CHECKABLE_ITEM_KEYS.contains(itemKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Not a checkable stockpile item: " + itemKey);
        }
        if (stockpileItemRepo.existsByHouseholdIdAndItemKey(householdId, itemKey)) {
            stockpileItemRepo.deleteByHouseholdIdAndItemKey(householdId, itemKey);
        } else {
            // Idempotent upsert (INSERT ... ON CONFLICT DO NOTHING): a concurrent
            // double-check from another member is a clean no-op, never a
            // constraint violation. Catching the violation here would NOT work —
            // on Postgres the failed INSERT aborts the transaction, so the
            // getForHousehold read below would then fail with a 500 (T-16).
            stockpileItemRepo.insertIfAbsent(householdId, itemKey);
        }
        return getForHousehold(householdId);
    }

    /**
     * Overlays a household's persisted check-off state onto a freshly-built
     * NON-FOOD category: each item's {@code satisfied} becomes "row exists", the
     * category is satisfied when all its items are, and it is now trackable.
     * (Food &amp; Water is derived and never passes through here.)
     */
    private static StockpileCategoryDto applyChecked(StockpileCategoryDto c, Set<String> checked) {
        List<StockpileItemDto> items = c.items().stream()
                .map(it -> new StockpileItemDto(it.itemKey(), it.label(), it.qtyRecommended(),
                        it.unit(), it.priority(), it.detail(), checked.contains(it.itemKey())))
                .toList();
        boolean satisfied = !items.isEmpty() && items.stream().allMatch(StockpileItemDto::satisfied);
        return new StockpileCategoryDto(c.key(), c.label(), c.blurb(), true, satisfied,
                c.cta(), c.route(), items);
    }

    // ── Category builders (demographic-scaled, backend-authored copy) ───────────

    private StockpileCategoryDto foodWaterCategory(boolean satisfied) {
        List<StockpileItemDto> items = List.of(
                item("stockpile-water-14d", "Two weeks of stored water", 0, "", 0,
                        "1 gallon per person per day for 14 days. Planned in the Food Planner "
                                + "so the water + food math stays in one place.", satisfied),
                item("stockpile-food-14d", "Two weeks of shelf-stable food", 0, "", 0,
                        "A full 14-day non-perishable menu for everyone at home.", satisfied)
        );
        return new StockpileCategoryDto(
                "food_water", "Food & Water (14-day)",
                "The core of a stay-home reserve — the one part with real quantity math, "
                        + "which the Food Planner already owns.",
                true, satisfied,
                satisfied ? null : "Build 14-day food plan",
                satisfied ? null : FOOD_PLAN_ROUTE,
                items);
    }

    private StockpileCategoryDto sanitationCategory(int persons, int infants, int pets) {
        List<StockpileItemDto> items = new ArrayList<>();
        items.add(item("stockpile-toilet-paper", "Toilet paper", Math.max(2, persons * 2), "rolls", 0,
                "About 2 rolls per person for two weeks.", false));
        items.add(item("stockpile-hand-sanitizer", "Hand sanitizer", Math.max(1, persons), "bottles", 0,
                "One bottle per person when running water may be limited.", false));
        items.add(item("stockpile-soap-detergent", "Soap & detergent", Math.max(2, persons), "units", 1,
                "Hand soap plus dish and laundry detergent.", false));
        items.add(item("stockpile-garbage-bags", "Heavy-duty garbage bags", Math.max(2, persons), "boxes", 1,
                "For waste and emergency sanitation.", false));
        items.add(item("stockpile-bleach", "Unscented bleach", 1, "bottle", 1,
                "Disinfecting surfaces and emergency water treatment.", false));
        if (infants > 0) {
            items.add(item("stockpile-diapers", "Diapers", infants * 84, "diapers", 0,
                    "~6 diapers per infant per day for 14 days.", false));
            items.add(item("stockpile-baby-wipes", "Baby wipes", Math.max(1, infants) * 2, "packs", 1,
                    "For diapering and cleanup without running water.", false));
        }
        if (pets > 0) {
            items.add(item("stockpile-pet-sanitation", "Pet waste supplies", Math.max(1, pets), "supplies", 2,
                    "Litter or waste bags so pets can stay indoors.", false));
        }
        return new StockpileCategoryDto("sanitation", "Sanitation & Hygiene",
                "Two weeks of staying clean and managing waste indoors.",
                false, false, null, null, items);
    }

    private StockpileCategoryDto powerHeatCategory(int persons) {
        List<StockpileItemDto> items = List.of(
                item("stockpile-flashlights", "Flashlights", Math.max(2, persons), "flashlights", 0,
                        "One per person plus a spare — never candles as the primary light.", false),
                item("stockpile-batteries", "Assorted batteries", Math.max(8, persons * 4), "batteries", 0,
                        "For flashlights, radio, and medical devices.", false),
                item("stockpile-manual-can-opener", "Manual can opener", 1, "opener", 0,
                        "Non-electric — you cannot eat a food reserve without one.", false),
                item("stockpile-lantern", "Battery / solar lantern", Math.max(1, (int) Math.ceil(persons / 3.0)), "lanterns", 1,
                        "Area light for shared living space.", false),
                item("stockpile-power-station", "Backup power bank / station", 1, "unit", 1,
                        "Keeps phones and medical devices (e.g. CPAP) running through an outage.", false),
                item("stockpile-warm-blankets", "Warm blankets / sleeping bags", Math.max(2, persons), "blankets", 1,
                        "Warmth if heat fails — one per person plus spares.", false),
                item("stockpile-co-detector", "Battery CO detector", 1, "detector", 0,
                        "Required if you ever use an alternate heat source or generator.", false)
        );
        return new StockpileCategoryDto("power_heat", "Power & Heat",
                "Light, backup power, and warmth for a two-week outage.",
                false, false, null, null, items);
    }

    private StockpileCategoryDto medicalCategory(int persons) {
        List<StockpileItemDto> items = List.of(
                item("stockpile-first-aid-kit", "Comprehensive first-aid kit", 1, "kit", 0,
                        "A full home kit (200+ pieces) — larger than the go-bag kit.", false),
                item("stockpile-rx-14day", "14-day prescription cushion", Math.max(1, persons), "person-supplies", 0,
                        "Two extra weeks of every prescription, for each person.", false),
                item("stockpile-otc-meds", "Over-the-counter medicine kit", 1, "kit", 1,
                        "Pain reliever, anti-diarrheal, antihistamine, and antacid.", false),
                item("stockpile-electrolytes", "Electrolyte / rehydration supplies", Math.max(2, persons), "supplies", 1,
                        "Oral rehydration for illness or heat.", false),
                item("stockpile-n95-masks", "N95 respirators", Math.max(2, persons * 3), "masks", 1,
                        "Smoke, dust, or illness during a long stay-home stretch.", false),
                item("stockpile-thermometer", "Thermometer", 1, "thermometer", 1,
                        "To monitor illness when clinics are hard to reach.", false)
        );
        return new StockpileCategoryDto("medical", "Medical",
                "Two weeks of treating illness and injury at home.",
                false, false, null, null, items);
    }

    private StockpileCategoryDto toolsSafetyCategory(int adultsTeens) {
        List<StockpileItemDto> items = List.of(
                item("stockpile-fire-extinguisher", "Fire extinguisher", 1, "extinguisher", 0,
                        "ABC-rated, in an accessible spot.", false),
                item("stockpile-noaa-radio", "NOAA weather radio", 1, "radio", 0,
                        "Hand-crank / battery alerts — separate unit from your go-bag radio.", false),
                item("stockpile-utility-shutoff-wrench", "Utility shutoff wrench", 1, "wrench", 0,
                        "To shut off gas and water at the source.", false),
                item("stockpile-multi-tool", "Multi-tool", 1, "tool", 1,
                        "General repairs and improvised fixes.", false),
                item("stockpile-duct-tape-sheeting", "Duct tape & plastic sheeting", 1, "set", 1,
                        "Seal a room to shelter in place against smoke or chemicals.", false),
                item("stockpile-work-gloves", "Work gloves", Math.max(1, adultsTeens), "pairs", 1,
                        "Cleanup and debris handling — one pair per capable adult/teen.", false),
                item("stockpile-document-binder", "Waterproof document binder", 1, "binder", 1,
                        "Insurance, IDs, medical and financial records, plus emergency cash.", false)
        );
        return new StockpileCategoryDto("tools_safety", "Tools & Safety",
                "The gear and records that turn a home into a safe shelter.",
                false, false, null, null, items);
    }

    private static StockpileItemDto item(String key, String label, int qty, String unit,
                                         int priority, String detail, boolean satisfied) {
        return new StockpileItemDto(key, label, qty, unit, priority, detail, satisfied);
    }
}
