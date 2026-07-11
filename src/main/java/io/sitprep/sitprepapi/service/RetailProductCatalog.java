package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.dto.RetailProductDto;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single source of truth for the emergency-food product catalog —
 * package sizes/units, retailer links, and labels. Extracted from
 * {@code RetailProductResource} (Thin-Client Refactor Phase 2) so BOTH the
 * public {@code /api/retail/products} endpoint AND
 * {@link FoodPlanCalculatorService} consume one list. This is where the
 * "18 oz vs 8 oz cereal" client drift gets resolved — the calculator now
 * sizes packages from these canonical values, not a divergent per-file map.
 *
 * <p>Package sizes mirror the frontend {@code productRegistry.js} exactly
 * (Water 5.3 gal = 678.4 fl oz, Cereal 18 oz, etc.). Serving-size math is
 * separate ({@link FoodPlanCalculatorService} owns the per-demographic
 * serving table).</p>
 */
@Component
public class RetailProductCatalog {

    public static final String VERIFIED = "2026-06-03";

    private final List<RetailProductDto> products = List.of(
            product("Water", "gal", "One gallon per person per day. Prefer sealed water or food-grade storage.", "B07FVJLYZ4", "https://www.amazon.com/s?k=emergency+water+storage", null, "https://www.walmart.com/search?q=emergency%20water%20storage", 5.3 * 128, "fl oz", "5.3 gal container"),
            product("Tuna", "oz", "Shelf-stable protein. Choose pull-tab cans or keep a manual can opener.", "B000Q62PQA", "https://www.amazon.com/s?k=canned+tuna+5+oz", null, "https://www.walmart.com/search?q=canned%20tuna%205%20oz", 5, "oz", "5 oz can"),
            product("Chili", "oz", "Ready-to-eat calories and protein. Lower-sodium cans are easier on water needs.", null, "https://www.amazon.com/s?k=canned+chili+15+oz", null, "https://www.walmart.com/search?q=canned%20chili%2015%20oz", 15, "oz", "15 oz can"),
            product("Beef Stew", "oz", "Ready-to-eat meal base. Look for pull-tab or family-size cans.", "B06Y1CZ9JL", "https://www.amazon.com/s?k=canned+beef+stew", null, "https://www.walmart.com/search?q=canned%20beef%20stew", 27, "oz", "27 oz can"),
            product("Ravioli", "oz", "Familiar comfort food with calories, sauce, and no added water required.", null, "https://www.amazon.com/s?k=canned+ravioli", null, "https://www.walmart.com/search?q=canned%20ravioli", 15, "oz", "15 oz can"),
            product("Tofu", "oz", "Shelf-stable plant protein where available; otherwise swap for beans.", null, "https://www.amazon.com/s?k=shelf+stable+tofu", null, "https://www.walmart.com/search?q=shelf%20stable%20tofu", 12, "oz", "12 oz pack"),
            product("Veggies", "oz", "Canned vegetables with liquid help stretch meals without cooking.", null, "https://www.amazon.com/s?k=low+sodium+canned+vegetables", null, "https://www.walmart.com/search?q=low%20sodium%20canned%20vegetables", 15, "oz", "15 oz can"),
            product("Fruit", "oz", "Canned fruit or fruit cups add calories, fluid, and morale.", "B074H6MBQQ", "https://www.amazon.com/s?k=canned+fruit+in+juice", null, "https://www.walmart.com/search?q=canned%20fruit%20in%20juice", 64, "oz", "multi-pack"),
            product("Nuts", "oz", "Compact calories. Avoid if household allergies are possible.", null, "https://www.amazon.com/s?k=unsalted+mixed+nuts", null, "https://www.walmart.com/search?q=unsalted%20mixed%20nuts", 16, "oz", "16 oz jar"),
            product("Granola Bar", "bars", "Portable snack calories for adults, teens, and kids.", "B0886WWGCB", "https://www.amazon.com/s?k=granola+bars+variety+pack", null, "https://www.walmart.com/search?q=granola%20bars%20variety%20pack", 12, "bars", "12 count box"),
            product("Cereal", "oz", "Ready-to-eat cereal or oats. Store in pest-resistant containers after opening.", null, "https://www.amazon.com/s?k=ready+to+eat+cereal", null, "https://www.walmart.com/search?q=ready%20to%20eat%20cereal", 18, "oz", "18 oz box"),
            product("Peanut Butter", "oz", "High-calorie protein. Use seed butter if allergies are a concern.", null, "https://www.amazon.com/s?k=peanut+butter+16+oz", null, "https://www.walmart.com/search?q=peanut%20butter%2016%20oz", 16, "oz", "16 oz jar"),
            product("Crackers", "pieces", "Low-sodium or whole-grain crackers pair with proteins and nut butters.", null, "https://www.amazon.com/s?k=whole+grain+crackers", null, "https://www.walmart.com/search?q=whole%20grain%20crackers", 120, "pieces", "family box"),
            product("Juice", "fl oz", "Shelf-stable 100% juice adds calories and variety.", null, "https://www.amazon.com/s?k=shelf+stable+juice", null, "https://www.walmart.com/search?q=shelf%20stable%20juice", 64, "fl oz", "64 fl oz bottle"),
            product("Milk", "fl oz", "Shelf-stable milk or boxed milk for cereal and calories.", null, "https://www.amazon.com/s?k=shelf+stable+milk", null, "https://www.walmart.com/search?q=shelf%20stable%20milk", 48, "fl oz", "6 pack"),
            product("Baby Formula", "fl oz", "Ready-to-feed formula is safest in emergencies when breastfeeding is not possible.", null, "https://www.amazon.com/s?k=ready+to+feed+infant+formula", null, "https://www.walmart.com/search?q=ready%20to%20feed%20infant%20formula", 32, "fl oz", "ready-to-feed bottle"),
            product("Baby Food", "oz", "Pouches or jars by age; rotate often and pack disposable feeding supplies.", null, "https://www.amazon.com/s?k=baby+food+pouches", null, "https://www.walmart.com/search?q=baby%20food%20pouches", 4, "oz", "4 oz pouch"),
            product("Worth of dog food", "days", "Keep the food your dog already eats; sudden switches can cause stomach issues.", null, "https://www.amazon.com/s?k=dog+food", null, "https://www.walmart.com/search?q=dog%20food", 1, "days", "1 day supply"),
            product("Worth of cat food", "days", "Keep the food your cat already eats plus a carrier-ready bowl.", null, "https://www.amazon.com/s?k=cat+food", null, "https://www.walmart.com/search?q=cat%20food", 1, "days", "1 day supply"),
            product("Worth of small/other pet food", "days", "Use the regular food for other pets; include water, bedding, and habitat needs.", null, "https://www.amazon.com/s?k=pet+food", null, "https://www.walmart.com/search?q=pet%20food", 1, "days", "1 day supply")
    );

    private final Map<String, RetailProductDto> byItem;

    public RetailProductCatalog() {
        Map<String, RetailProductDto> m = new LinkedHashMap<>();
        for (RetailProductDto p : products) m.put(p.item(), p);
        this.byItem = Map.copyOf(m);
    }

    public List<RetailProductDto> all() {
        return products;
    }

    /** Catalog entry for an item name, or null if unknown. */
    public RetailProductDto byItem(String item) {
        return item == null ? null : byItem.get(item);
    }

    private static RetailProductDto product(
            String item, String unit, String description,
            String amazonAsin, String amazonSearchUrl,
            String walmartItemId, String walmartSearchUrl,
            double packageSize, String packageUnit, String packageLabel) {
        String buyLink = amazonAsin == null
                ? amazonSearchUrl
                : "https://www.amazon.com/dp/" + amazonAsin;
        return new RetailProductDto(
                item, unit, description, buyLink, null,
                amazonAsin, amazonSearchUrl, walmartItemId, walmartSearchUrl,
                packageSize, packageUnit, packageLabel, VERIFIED);
    }
}
