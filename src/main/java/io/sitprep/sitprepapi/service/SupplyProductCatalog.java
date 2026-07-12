package io.sitprep.sitprepapi.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Server-side commerce catalog for the 14-Day Home Kit / Advanced-Readiness
 * supply items — the supply-side twin of {@link GoBagProductCatalog}. Keyed by
 * the {@code stockpile-*} itemKeys emitted by {@link HomeStockpileService}, it
 * returns FINAL tagged retailer URLs so the FE does zero URL construction (the
 * canonical BE-authored pattern).
 *
 * <p><b>URL rules (mirror the Go Bag catalog exactly):</b> we ship tagged
 * Amazon <em>search</em> links — no ASINs are hard-coded, because none have
 * been human-verified for these items yet, and the integrity rule forbids a
 * fabricated ASIN. When verified ASINs land, set them here and the shared cart
 * engine will batch them into a real "add to cart" instead of opening a search.
 * Walmart is a plain search link until the Walmart/Impact program exists. The
 * Amazon Associates tag comes from {@code app.commerce.amazon-associate-tag}
 * (default {@code sitprep0f-20}).</p>
 *
 * <p>Every FE surface rendering these links must show the "As an Amazon
 * Associate…" disclosure + an "Affiliated" pill, and commerce is suppressed in
 * crisis contexts via {@link CommerceSuppressionService}.</p>
 */
@Component
public class SupplyProductCatalog {

    /** When a human last reviewed these search mappings (not a live check). */
    public static final String LAST_REVIEWED_AT = "2026-07-12";

    public record Product(
            String key,
            String label,
            /** Verified ASIN, or null when the Amazon link is a search fallback. */
            String amazonAsin,
            /** Final, tagged Amazon URL (search fallback today). */
            String amazonUrl,
            /** Plain Walmart search URL (no affiliate program yet). */
            String walmartUrl,
            String lastReviewedAt
    ) {}

    private final String associateTag;
    private final Map<String, Product> byKey = new LinkedHashMap<>();

    public SupplyProductCatalog(
            @Value("${app.commerce.amazon-associate-tag:sitprep0f-20}") String associateTag) {
        this.associateTag = associateTag;

        // ── Sanitation & hygiene ──
        add("stockpile-toilet-paper", "Toilet paper", "toilet paper bulk pack");
        add("stockpile-hand-sanitizer", "Hand sanitizer", "hand sanitizer");
        add("stockpile-soap-detergent", "Soap & detergent", "soap and laundry detergent");
        add("stockpile-garbage-bags", "Heavy-duty garbage bags", "heavy duty contractor garbage bags");
        add("stockpile-bleach", "Unscented bleach", "unscented bleach");
        add("stockpile-diapers", "Diapers", "diapers");
        add("stockpile-baby-wipes", "Baby wipes", "baby wipes bulk");
        add("stockpile-pet-sanitation", "Pet waste supplies", "pet waste bags");

        // ── Power & heat ──
        add("stockpile-flashlights", "Flashlights", "led flashlight");
        add("stockpile-batteries", "Assorted batteries", "assorted batteries aa aaa d c");
        add("stockpile-manual-can-opener", "Manual can opener", "manual can opener");
        add("stockpile-lantern", "Battery / solar lantern", "battery solar camping lantern");
        add("stockpile-power-station", "Backup power bank / station", "portable power station");
        add("stockpile-warm-blankets", "Warm blankets / sleeping bags", "emergency wool blanket sleeping bag");
        add("stockpile-co-detector", "Battery CO detector", "battery carbon monoxide detector");

        // ── Medical ──
        add("stockpile-first-aid-kit", "Comprehensive first-aid kit", "comprehensive first aid kit");
        add("stockpile-rx-14day", "14-day prescription cushion", "14 day pill organizer");
        add("stockpile-otc-meds", "Over-the-counter medicine kit", "over the counter medicine kit");
        add("stockpile-electrolytes", "Electrolyte / rehydration supplies", "electrolyte powder packets");
        add("stockpile-n95-masks", "N95 respirators", "n95 respirator masks niosh");
        add("stockpile-thermometer", "Thermometer", "digital thermometer");

        // ── Tools & safety ──
        add("stockpile-fire-extinguisher", "Fire extinguisher", "home fire extinguisher");
        add("stockpile-noaa-radio", "NOAA weather radio", "noaa weather radio hand crank");
        add("stockpile-utility-shutoff-wrench", "Utility shutoff wrench", "gas water utility shutoff wrench");
        add("stockpile-multi-tool", "Multi-tool", "multi tool pliers");
        add("stockpile-duct-tape-sheeting", "Duct tape & plastic sheeting", "duct tape plastic sheeting");
        add("stockpile-work-gloves", "Work gloves", "work gloves");
        add("stockpile-document-binder", "Waterproof document binder", "waterproof document bag zip");
    }

    private void add(String key, String label, String query) {
        addAsin(key, label, null, query);
    }

    /** Reserved for when a human-verified ASIN lands — ships a /dp/ cart-addable link. */
    private void addAsin(String key, String label, String asin, String query) {
        String amazonUrl = asin != null
                ? "https://www.amazon.com/dp/" + asin + "?tag=" + associateTag
                : "https://www.amazon.com/s?k=" + enc(query) + "&tag=" + associateTag;
        String walmartUrl = "https://www.walmart.com/search?q=" + enc(query);
        byKey.put(key, new Product(key, label, asin, amazonUrl, walmartUrl, LAST_REVIEWED_AT));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public Optional<Product> byKey(String key) {
        return Optional.ofNullable(byKey.get(key));
    }
}
