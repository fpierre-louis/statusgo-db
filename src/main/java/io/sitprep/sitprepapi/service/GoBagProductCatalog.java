package io.sitprep.sitprepapi.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Canonical go-bag commerce catalog — the server-side source of truth for
 * retailer links, ported 1:1 from the FE {@code me/plans/shared/productRegistry.js}
 * {@code GO_BAG_PRODUCT_LIST} (which is now deprecated for go-bag commerce).
 * Keyed by the {@code productKey} strings persisted on {@code GoBagItem} and
 * emitted on {@code RecommendedItemDto}/{@code StrategyDto}.
 *
 * <p>URL rules (mirror the FE registry exactly): only ASINs verified in
 * GO_BAG_RESEARCH_AND_SOURCING.md §8 get a {@code /dp/} link; everything else
 * falls back to a tagged Amazon search — never a fabricated ASIN. Walmart is a
 * plain search link until the Walmart/Impact affiliate program exists. The
 * Amazon Associates tag comes from {@code app.commerce.amazon-associate-tag}
 * (default {@code sitprep0f-20}).</p>
 *
 * <p>{@code lastReviewedAt} records when a human last reviewed the mappings —
 * we do not verify live pricing/stock dynamically.</p>
 *
 * <p>Monetization-integrity note (VISION_AND_SCOPE rule 6): every FE surface
 * rendering these links must show the "As an Amazon Associate…" disclosure and
 * an "Affiliated" pill, and all commerce is suppressed in crisis contexts
 * ({@link GoBagSupplyListService} nulls the links + flags the payload).</p>
 */
@Component
public class GoBagProductCatalog {

    /** When a human last reviewed these mappings (not a live-verification stamp). */
    public static final String LAST_REVIEWED_AT = "2026-07-08";

    public record Product(
            String key,
            String label,
            /** Verified ASIN, or null when the Amazon link is a search fallback. */
            String amazonAsin,
            /** Final, tagged Amazon URL. */
            String amazonUrl,
            /** Plain Walmart search URL (no affiliate program yet). */
            String walmartUrl,
            String lastReviewedAt
    ) {}

    private final String associateTag;
    private final Map<String, Product> byKey = new LinkedHashMap<>();

    public GoBagProductCatalog(
            @Value("${app.commerce.amazon-associate-tag:sitprep0f-20}") String associateTag) {
        this.associateTag = associateTag;

        // Checklist items
        add("gobag-water-pouches", "Emergency water pouches", null,
                "emergency water pouches 5 year datrex", null);
        add("gobag-water-filter", "Portable water filter (Sawyer Mini)", null,
                "sawyer mini water filter", null);
        add("gobag-water-tablets", "Water purification tablets", null,
                "aquatabs water purification tablets", null);
        add("gobag-ration-bars", "Datrex 3,600-cal ration bar", "B001CSAHW0",
                null, "emergency food ration bar 3600");
        add("gobag-first-aid-kit", "First-aid kit (200+ pc)", null,
                "first aid kit 200 piece", "first aid only 299 piece");
        add("gobag-headlamp", "Headlamp or flashlight", null,
                "led headlamp emergency", null);
        add("gobag-noaa-radio", "NOAA weather radio (Midland ER210)", null,
                "midland er210 emergency weather radio", null);
        add("gobag-power-bank", "Power bank", null,
                "anker power bank 10000mah", null);
        add("gobag-emergency-bivvy", "SOL emergency bivvy", "B00TW2CXZM",
                null, "emergency bivvy sleeping bag mylar");
        add("gobag-hand-warmers", "Hand warmers (10 pk)", "B0GHNN4JNQ",
                null, "hothands hand warmers 10 pack");
        add("gobag-multi-tool", "Multi-tool", null,
                "leatherman multi tool", null);
        add("gobag-backpack", "Backpack (5.11 RUSH24)", null,
                "5.11 rush24 backpack", null);
        // Pre-made kits (strategy step)
        add("gobag-kit-ready-america", "Ready America 2-person 72-hr kit", "B000FJQQVI",
                null, "ready america emergency kit 2 person");
        add("gobag-kit-sustain-essential-2", "Sustain Supply Essential 2-person kit", "B0C37W2RPX",
                null, "sustain supply emergency kit");
        add("gobag-kit-emergency-zone", "Emergency Zone Urban Survival kit", null,
                "emergency zone urban survival bug out bag 2 person", "emergency zone survival kit");
    }

    public Optional<Product> byKey(String productKey) {
        return productKey == null ? Optional.empty()
                : Optional.ofNullable(byKey.get(productKey));
    }

    public List<Product> all() {
        return List.copyOf(byKey.values());
    }

    // ---------------------------------------------------------------------

    /** @param query   Amazon search fallback; ignored when {@code asin} is set (label used when null).
     *  @param walmartQuery Walmart search term; falls back to query, then label. */
    private void add(String key, String label, String asin, String query, String walmartQuery) {
        String amazonUrl = asin != null
                ? "https://www.amazon.com/dp/" + asin + "?tag=" + associateTag
                : "https://www.amazon.com/s?k=" + enc(query != null ? query : label)
                        + "&tag=" + associateTag;
        String walmartUrl = "https://www.walmart.com/search?q="
                + enc(walmartQuery != null ? walmartQuery : (query != null ? query : label));
        byKey.put(key, new Product(key, label, asin, amazonUrl, walmartUrl, LAST_REVIEWED_AT));
    }

    private static String enc(String s) {
        // URLEncoder uses '+' for spaces (the FE used %20 via encodeURIComponent);
        // both are equivalent in a query component for Amazon/Walmart search.
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
