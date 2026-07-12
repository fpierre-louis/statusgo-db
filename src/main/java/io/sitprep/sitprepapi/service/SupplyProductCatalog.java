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
 * <p><b>URL rules (mirror the Go Bag catalog exactly):</b> items with a known
 * ASIN ship a tagged {@code /dp/} link that the shared cart engine batches into
 * a real Amazon "add to cart"; the rest ship a tagged Amazon <em>search</em>
 * link (still functional — it just opens a search). Walmart is always a plain
 * search link until the Walmart/Impact program exists. The Amazon Associates
 * tag comes from {@code app.commerce.amazon-associate-tag} (default
 * {@code sitprep0f-20}).</p>
 *
 * <p><b>⚠ ASINs are REPRESENTATIVE — verify before launch.</b> The hard-coded
 * ASINs below (batteries, first-aid kit, N95, NOAA radio, shutoff wrench, duct
 * tape) are well-known emergency products chosen to make the batched cart
 * functional for QA, but they are NOT live-verified. Confirm each maps to a
 * good, in-stock listing (and bump {@link #LAST_REVIEWED_AT}) before the public
 * launch — same discipline as the food registry's {@code
 * PRODUCT_REGISTRY_VERIFIED_AT}. Unverified items correctly stay on search.</p>
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
        // AmazonBasics AA batteries, 48-pack — a batched-cart-addable ASIN.
        addAsin("stockpile-batteries", "Assorted batteries", "B00MNV8E0C", "assorted batteries aa aaa d c");
        add("stockpile-manual-can-opener", "Manual can opener", "manual can opener");
        add("stockpile-lantern", "Battery / solar lantern", "battery solar camping lantern");
        add("stockpile-power-station", "Backup power bank / station", "portable power station");
        add("stockpile-warm-blankets", "Warm blankets / sleeping bags", "emergency wool blanket sleeping bag");
        add("stockpile-co-detector", "Battery CO detector", "battery carbon monoxide detector");

        // ── Medical ──
        // First Aid Only 299-piece all-purpose kit.
        addAsin("stockpile-first-aid-kit", "Comprehensive first-aid kit", "B00NA5UN2K", "comprehensive first aid kit");
        add("stockpile-rx-14day", "14-day prescription cushion", "14 day pill organizer");
        add("stockpile-otc-meds", "Over-the-counter medicine kit", "over the counter medicine kit");
        add("stockpile-electrolytes", "Electrolyte / rehydration supplies", "electrolyte powder packets");
        // 3M-style NIOSH N95 respirators, boxed.
        addAsin("stockpile-n95-masks", "N95 respirators", "B08L7RH2JK", "n95 respirator masks niosh");
        add("stockpile-thermometer", "Thermometer", "digital thermometer");

        // ── Tools & safety ──
        add("stockpile-fire-extinguisher", "Fire extinguisher", "home fire extinguisher");
        // Midland emergency hand-crank / NOAA weather radio.
        addAsin("stockpile-noaa-radio", "NOAA weather radio", "B0788FBRDD", "noaa weather radio hand crank");
        // 4-in-1 gas + water utility shutoff wrench.
        addAsin("stockpile-utility-shutoff-wrench", "Utility shutoff wrench", "B0002YW3AA", "gas water utility shutoff wrench");
        add("stockpile-multi-tool", "Multi-tool", "multi tool pliers");
        // Duck brand duct tape (pairs with plastic sheeting).
        addAsin("stockpile-duct-tape-sheeting", "Duct tape & plastic sheeting", "B0006MVOZ8", "duct tape plastic sheeting");
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
