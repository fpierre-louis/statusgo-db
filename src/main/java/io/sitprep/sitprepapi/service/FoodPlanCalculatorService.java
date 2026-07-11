package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.domain.MealPlan;
import io.sitprep.sitprepapi.domain.MealPlanData;
import io.sitprep.sitprepapi.domain.PlanDuration;
import io.sitprep.sitprepapi.dto.FoodPlanDtos.*;
import io.sitprep.sitprepapi.dto.RetailProductDto;
import io.sitprep.sitprepapi.repo.DemographicRepo;
import io.sitprep.sitprepapi.repo.MealPlanDataRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Backend Food Planner "smart calculator" (Thin-Client Refactor Phase 2).
 * Reads the household's {@link Demographic} + saved {@link MealPlanData}
 * (menu + duration) and the shared {@link RetailProductCatalog}, and returns
 * a fully-computed shopping list — the single source of truth that replaces
 * the client-side {@code useCalculatedList} / {@code PrintableFoodMenu} math.
 *
 * <p><b>Faithful 1:1 port</b> of the old {@code useCalculatedList}
 * (docs/architecture/THIN_CLIENT_REFACTOR_ROADMAP.md Phase 2), so numbers do
 * not shift on cutover — with the one intentional correction that resolves
 * the "18 oz vs 8 oz cereal" drift by sourcing package sizes from the single
 * {@link RetailProductCatalog} instead of divergent per-file maps. Ported
 * behaviors preserved deliberately:
 * <ul>
 *   <li>teens reuse the ADULT serving table;</li>
 *   <li>dogs/cats/pets contribute nothing through the menu loop (the old JS
 *       {@code determineServingSize} ignored those args) — pet food is added
 *       only by the inline per-day block;</li>
 *   <li>Water is never in a meal mapping — its whole quantity is the inline
 *       1 gal/adult|teen|kid/day + 0.5/infant/day block;</li>
 *   <li>ingredients with no catalog entry (e.g. "Dried Fruits") are dropped.</li>
 * </ul>
 */
@Service
public class FoodPlanCalculatorService {

    private final DemographicRepo demographicRepo;
    private final MealPlanDataRepo mealPlanDataRepo;
    private final RetailProductCatalog catalog;

    public FoodPlanCalculatorService(DemographicRepo demographicRepo,
                                     MealPlanDataRepo mealPlanDataRepo,
                                     RetailProductCatalog catalog) {
        this.demographicRepo = demographicRepo;
        this.mealPlanDataRepo = mealPlanDataRepo;
        this.catalog = catalog;
    }

    // Priority sort (FE PRIORITY): Water=1, Baby Formula=2, Baby Food=3, else 99.
    private static final Map<String, Integer> PRIORITY = Map.of(
            "Water", 1, "Baby Formula", 2, "Baby Food", 3);

    private static final List<String> DEMO_KEYS =
            List.of("adults", "teens", "kids", "infants", "dogs", "cats", "pets");

    // ── per-demographic serving tables (verbatim from foodMath.js) ──────────
    private static final Map<String, Double> ADULT = Map.ofEntries(
            e("Cereal", 8), e("Milk", 8), e("Juice", 8), e("Tuna", 5), e("Chili", 8),
            e("Beef Stew", 8), e("Ravioli", 8), e("Tofu", 4), e("Veggies", 4), e("Fruit", 4),
            e("Nuts", 0.25), e("Granola Bar", 1), e("Peanut Butter", 2), e("Crackers", 6), e("Water", 1));
    private static final Map<String, Double> CHILD = Map.ofEntries(
            e("Cereal", 4), e("Milk", 4), e("Juice", 4), e("Tuna", 2.5), e("Chili", 4),
            e("Beef Stew", 4), e("Ravioli", 4), e("Tofu", 2), e("Veggies", 2), e("Fruit", 2),
            e("Nuts", 0.125), e("Granola Bar", 0.5), e("Peanut Butter", 1), e("Crackers", 3), e("Water", 1));
    private static final Map<String, Double> INFANT = Map.of(
            "Baby Food", 12.0, "Baby Formula", 18.0, "Water", 0.5);

    // ingredientToItemMap is identity for these 17 keys; anything else (e.g.
    // "Dried Fruits") has no mapping and is dropped, exactly like the FE.
    private static final java.util.Set<String> KNOWN_INGREDIENTS = java.util.Set.of(
            "Cereal", "Milk", "Fruit", "Nuts", "Granola Bar", "Tuna", "Chili", "Beef Stew",
            "Ravioli", "Tofu", "Veggies", "Peanut Butter", "Crackers", "Baby Food",
            "Baby Formula", "Water", "Juice");

    // Meal display-name → ingredient list, ported verbatim from the FE
    // mealCatalog.mapMealToIngredients. The backend now DERIVES a menu's
    // ingredients from the chosen meal NAMES (always saved) instead of trusting
    // the FE to have computed and persisted a per-menu `ingredients` map. That
    // coupling was fragile: whenever the saved `ingredients` map was empty
    // (stale pre-feature rows, a save path that didn't run mapMealToIngredients,
    // cross-household edits), EVERY meal item was silently dropped and the list
    // collapsed to just the demographic baselines (Water, pet/baby food). Making
    // meal→ingredient expansion authoritative on the server fixes that and is
    // the correct thin-client shape. Ingredients absent from KNOWN_INGREDIENTS /
    // the catalog (e.g. "Dried Fruits") are still dropped downstream, as the FE did.
    private static final Map<String, List<String>> MEAL_INGREDIENTS = Map.ofEntries(
            e("Cereal with Milk and Fruit", "Cereal", "Milk", "Fruit", "Juice"),
            e("Cereal with Milk, Fruit, and Nuts", "Cereal", "Milk", "Fruit", "Nuts", "Juice"),
            e("Cereal with Milk, Fruit, and Granola Bar", "Cereal", "Milk", "Fruit", "Granola Bar", "Juice"),
            e("Cereal with Milk, Fruit, and Peanut Butter", "Cereal", "Milk", "Fruit", "Peanut Butter", "Juice"),
            e("Tuna and Crackers Combo", "Tuna", "Crackers", "Fruit", "Veggies", "Juice"),
            e("Chilli and Crackers", "Chili", "Crackers", "Fruit", "Veggies", "Juice"),
            e("Beef Stew and Crackers Combo", "Beef Stew", "Crackers", "Fruit", "Veggies", "Juice"),
            e("Tofu with Veggies and Crackers", "Tofu", "Veggies", "Crackers", "Juice"),
            e("Ravioli with Fruits and Crackers", "Ravioli", "Fruit", "Crackers", "Juice"),
            e("Granola Bar", "Granola Bar"),
            e("Nuts or Dried Fruits", "Nuts", "Dried Fruits"),
            e("Crackers", "Crackers"),
            e("Peanut Butter and Crackers", "Peanut Butter", "Crackers"));

    private static Map.Entry<String, List<String>> e(String meal, String... ingredients) {
        return Map.entry(meal, List.of(ingredients));
    }

    private static Map.Entry<String, Double> e(String k, double v) {
        return Map.entry(k, v);
    }

    @Transactional(readOnly = true)
    public FoodPlanRecommendationDto recommendForHousehold(String householdId) {
        Demographic d = demographicRepo
                .findFirstByHouseholdIdOrderByIdDesc(householdId).orElse(null);
        int adults = d == null ? 0 : Math.max(0, d.getAdults());
        int teens = d == null ? 0 : Math.max(0, d.getTeens());
        int kids = d == null ? 0 : Math.max(0, d.getKids());
        int infants = d == null ? 0 : Math.max(0, d.getInfants());
        int dogs = d == null ? 0 : Math.max(0, d.getDogs());
        int cats = d == null ? 0 : Math.max(0, d.getCats());
        int pets = d == null ? 0 : Math.max(0, d.getPets());

        MealPlanData mpd = mealPlanDataRepo.findFirstByHouseholdId(householdId).orElse(null);
        PlanDuration pd = mpd == null ? null : mpd.getPlanDuration();
        int durQty = pd == null ? 3 : pd.getQuantity();
        String durUnit = (pd == null || pd.getUnit() == null) ? "Days" : pd.getUnit();
        int totalDays = calculateTotalDays(durQty, durUnit);

        List<MealPlan> menus = (mpd == null || mpd.getMealPlan() == null)
                ? List.of() : mpd.getMealPlan();

        Map<String, Integer> demoCounts = new LinkedHashMap<>();
        demoCounts.put("adults", adults);
        demoCounts.put("teens", teens);
        demoCounts.put("kids", kids);
        demoCounts.put("infants", infants);
        demoCounts.put("dogs", dogs);
        demoCounts.put("cats", cats);
        demoCounts.put("pets", pets);

        // itemName -> (group -> accumulated raw qty). LinkedHashMap for stable
        // pre-sort order.
        Map<String, Map<String, Double>> breakdownByItem = new LinkedHashMap<>();

        // ── menu day loop (rotates through the saved menus) ────────────────
        for (int day = 0; day < totalDays; day++) {
            if (menus.isEmpty()) continue;
            MealPlan menu = menus.get(day % menus.size());
            if (menu == null) continue;

            // Collect this menu's ingredients per meal slot. Prefer an
            // explicitly-saved ingredient list (legacy/custom data), otherwise
            // DERIVE from the chosen meal name via MEAL_INGREDIENTS. Deriving
            // from the reliably-saved meal names is what fixes the dropped-meal
            // regression — an empty saved `ingredients` map no longer wipes the
            // meal items (Cereal, Milk, Tuna, Beef Stew, …).
            List<String> ingredients = new ArrayList<>();
            Map<String, String> meals = menu.getMeals();
            Map<String, List<String>> savedIngredients = menu.getIngredients();
            java.util.Set<String> slots = new java.util.LinkedHashSet<>();
            if (meals != null) slots.addAll(meals.keySet());
            if (savedIngredients != null) slots.addAll(savedIngredients.keySet());
            for (String slot : slots) {
                List<String> saved = savedIngredients == null ? null : savedIngredients.get(slot);
                if (saved != null && !saved.isEmpty()) {
                    ingredients.addAll(saved);
                } else {
                    String mealName = meals == null ? null : meals.get(slot);
                    List<String> derived = mealName == null ? null : MEAL_INGREDIENTS.get(mealName);
                    if (derived != null) ingredients.addAll(derived);
                }
            }

            for (String ing : ingredients) {
                if (!KNOWN_INGREDIENTS.contains(ing)) continue;   // itemName undefined → drop
                if (catalog.byItem(ing) == null) continue;         // no catalog ref → drop
                Map<String, Double> bd = breakdownByItem.computeIfAbsent(ing, k -> new LinkedHashMap<>());
                for (String key : DEMO_KEYS) {
                    int count = demoCounts.getOrDefault(key, 0);
                    if (count == 0) continue;
                    double qty;
                    switch (key) {
                        case "adults", "teens" -> qty = count * ADULT.getOrDefault(ing, 0.0);
                        case "kids" -> qty = count * CHILD.getOrDefault(ing, 0.0);
                        case "infants" -> qty = count * INFANT.getOrDefault(ing, 0.0);
                        default -> qty = 0.0; // dogs/cats/pets → 0 via the loop (FE parity)
                    }
                    bd.merge(key, qty, Double::sum);
                }
            }
        }

        // ── inline additions (verbatim useCalculatedList) ──────────────────
        addItem(breakdownByItem, "Water", Map.of(
                "adults", adults * 1.0 * totalDays,
                "teens", teens * 1.0 * totalDays,
                "kids", kids * 1.0 * totalDays,
                "infants", infants * 0.5 * totalDays));
        if (infants > 0) {
            addItem(breakdownByItem, "Baby Food", Map.of("infants", infants * 12.0 * totalDays));
            addItem(breakdownByItem, "Baby Formula", Map.of("infants", infants * 18.0 * totalDays));
        }
        if (dogs > 0) addItem(breakdownByItem, "Worth of dog food", Map.of("dogs", dogs * 1.0 * totalDays));
        if (cats > 0) addItem(breakdownByItem, "Worth of cat food", Map.of("cats", cats * 1.0 * totalDays));
        if (pets > 0) addItem(breakdownByItem, "Worth of small/other pet food", Map.of("pets", pets * 1.0 * totalDays));

        // ── assemble items (filter totalRaw > 0, compute packages, sort) ───
        List<FoodPlanItemDto> items = new ArrayList<>();
        for (Map.Entry<String, Map<String, Double>> entry : breakdownByItem.entrySet()) {
            String item = entry.getKey();
            RetailProductDto ref = catalog.byItem(item);
            if (ref == null) continue;
            Map<String, Double> bd = entry.getValue();
            double totalRaw = bd.values().stream().mapToDouble(Double::doubleValue).sum();
            if (totalRaw <= 0) continue;

            List<BreakdownDto> breakdown = new ArrayList<>();
            for (String key : DEMO_KEYS) {
                double total = bd.getOrDefault(key, 0.0);
                if (total > 0) breakdown.add(new BreakdownDto(key, demoCounts.getOrDefault(key, 0), total));
            }

            int packages = packagesNeeded(totalRaw, ref.unit(), ref.packageUnit(), ref.packageSize());
            items.add(new FoodPlanItemDto(
                    item, ref.unit(), totalRaw, packages,
                    ref.packageSize(), ref.packageUnit(), ref.packageLabel(),
                    PRIORITY.getOrDefault(item, 99), breakdown));
        }

        items.sort((a, b) -> {
            int pa = a.priority(), pb = b.priority();
            if (pa != pb) return Integer.compare(pa, pb);
            return a.item().compareTo(b.item());
        });

        return new FoodPlanRecommendationDto(
                new DemographicSummaryDto(adults, teens, kids, infants, dogs, cats, pets,
                        adults + teens + kids + infants),
                totalDays,
                new PlanDurationDto(durQty, durUnit),
                items,
                new MetaDto(Instant.now(), RetailProductCatalog.VERIFIED));
    }

    private static void addItem(Map<String, Map<String, Double>> breakdownByItem,
                                String item, Map<String, Double> add) {
        Map<String, Double> bd = breakdownByItem.computeIfAbsent(item, k -> new LinkedHashMap<>());
        add.forEach((k, v) -> bd.merge(k, v, Double::sum));
    }

    /** {@code calculateTotalDays}: Weeks×7, Months×30, else quantity. */
    static int calculateTotalDays(int quantity, String unit) {
        if ("Weeks".equals(unit)) return quantity * 7;
        if ("Months".equals(unit)) return quantity * 30;
        return quantity;
    }

    /** Package count = max(1, ceil(convert(total, unit→packageUnit) / packageSize)). */
    static int packagesNeeded(double totalRaw, String unit, String packageUnit, double packageSize) {
        double normalized = normalizeToTargetUnit(totalRaw, unit, packageUnit);
        if (packageSize <= 0) return 1;
        return Math.max(1, (int) Math.ceil(normalized / packageSize));
    }

    private static String normalizeUnit(String u) {
        String s = u == null ? "" : u.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "ounce", "ounces" -> "oz";
            case "gallon", "gallons" -> "gal";
            case "bar", "bars" -> "bars";
            case "piece", "pieces" -> "pieces";
            default -> s;
        };
    }

    /** Verbatim port of useRetailerCart.normalizeToTargetUnit. */
    static double normalizeToTargetUnit(double total, String unit, String targetUnit) {
        String u = normalizeUnit(unit);
        String t = normalizeUnit(targetUnit);
        if (t.equals("bars") || t.equals("pieces") || t.equals("days")) return total;
        if (t.equals("fl oz")) {
            if (u.equals("gal")) return total * 128;
            if (u.equals("l") || u.equals("liter") || u.equals("liters")) return total * 33.814;
            if (u.equals("ml") || u.equals("milliliter") || u.equals("milliliters")) return total / 29.5735;
            return total; // fl oz / oz / unknown → passthrough
        }
        if (t.equals("oz")) {
            if (u.equals("lb") || u.equals("pound") || u.equals("pounds")) return total * 16;
            if (u.equals("tbsp") || u.equals("tablespoon") || u.equals("tablespoons")) return total / 2;
            return total; // oz / fl oz / unknown → passthrough
        }
        return total;
    }
}
