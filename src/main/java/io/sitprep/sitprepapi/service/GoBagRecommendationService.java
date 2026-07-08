package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.dto.GoBagDtos.ComputedNeedsDto;
import io.sitprep.sitprepapi.dto.GoBagDtos.DemographicSummaryDto;
import io.sitprep.sitprepapi.dto.GoBagDtos.RecommendationDto;
import io.sitprep.sitprepapi.dto.GoBagDtos.RecommendedItemDto;
import io.sitprep.sitprepapi.dto.GoBagDtos.StrategyDto;
import io.sitprep.sitprepapi.repo.DemographicRepo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/**
 * The Go Bag "Guided Assistant" brain — reads the household's
 * {@link Demographic} (adults / teens / kids / infants / dogs / cats /
 * pets), runs the scaling rules, and returns a fully personalized
 * {@link RecommendationDto}. The React wizard is a display layer over
 * this payload; the frontend does NO demographic math.
 *
 * <p>Every quantity rule and helper line is source-attributed in
 * {@code docs/features/GO_BAG_RESEARCH_AND_SOURCING.md} (Ready.gov /
 * FEMA / Red Cross / CDC / WA EMD / Cal OES / Be Ready Utah). Where
 * official sources disagree we hardcode the more conservative figure.
 * NOTE: no official calorie target exists — food scales in
 * days-of-supply, never kcal (do not "fix" that).</p>
 *
 * <p>Product identity ships as {@code productKey} strings that the
 * frontend {@code productRegistry} resolves to affiliate links. No
 * commercial URL originates here.</p>
 */
@Service
public class GoBagRecommendationService {

    /** 72-hour bag — the 3-day evacuation consensus (ARC, Cal OES). */
    public static final int PLAN_DAYS = 3;

    /** Warning horizon for the expiry sweep + "expiring soon" rollups. */
    public static final int EXPIRY_WARNING_DAYS = 30;

    private final DemographicRepo demographicRepo;

    public GoBagRecommendationService(DemographicRepo demographicRepo) {
        this.demographicRepo = demographicRepo;
    }

    // ---------------------------------------------------------------------
    // Scaling context
    // ---------------------------------------------------------------------

    /** Derived household counts the quantity rules scale against. */
    public record Ctx(int adults, int teens, int kids, int infants,
                      int dogs, int cats, int otherPets,
                      boolean seniorNeeds, boolean medicalNeeds) {
        public int persons()   { return adults + teens + kids + infants; }
        public int petsTotal() { return dogs + cats + otherPets; }
    }

    /** One baseline-checklist template row; {@code qty} scales it. */
    public record TemplateItem(String itemKey, String label, String category,
                               int priority, String unit,
                               Integer defaultShelfLifeDays, String helper,
                               String productKey, String appliesWhen,
                               ToIntFunction<Ctx> qty) {}

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /**
     * Build the personalized recommendation for a household. Throws 409
     * when the household has never been sized — the wizard's sizing step
     * gates on demographics existing and routes to the demographics
     * wizard first.
     */
    @Transactional(readOnly = true)
    public RecommendationDto recommendForHousehold(String householdId,
                                                   boolean seniorNeeds,
                                                   boolean medicalNeeds) {
        Demographic d = demographicRepo
                .findFirstByHouseholdIdOrderByIdDesc(householdId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Household has no demographics yet — size the household first"));
        Ctx c = new Ctx(
                Math.max(0, d.getAdults()), Math.max(0, d.getTeens()),
                Math.max(0, d.getKids()), Math.max(0, d.getInfants()),
                Math.max(0, d.getDogs()), Math.max(0, d.getCats()),
                Math.max(0, d.getPets()),
                seniorNeeds, medicalNeeds);
        return recommend(c);
    }

    /** Pure recommendation over an explicit context (testable seam). */
    public RecommendationDto recommend(Ctx c) {
        List<RecommendedItemDto> items = TEMPLATE.stream()
                .filter(t -> applies(t, c))
                .map(t -> new RecommendedItemDto(
                        t.itemKey(), t.label(), t.category(), t.priority(),
                        Math.max(0, t.qty().applyAsInt(c)), t.unit(),
                        t.defaultShelfLifeDays(), t.helper(), t.productKey(),
                        t.appliesWhen()))
                .filter(i -> i.qtyRecommended() > 0)
                .collect(Collectors.toList());

        return new RecommendationDto(
                new DemographicSummaryDto(c.adults(), c.teens(), c.kids(), c.infants(),
                        c.dogs(), c.cats(), c.otherPets(), c.persons(), c.petsTotal()),
                new ComputedNeedsDto(
                        waterGallons(c), PLAN_DAYS, "7–10 days",
                        c.persons(), c.petsTotal(), PLAN_DAYS),
                items,
                STRATEGIES);
    }

    /** 1 gal per person AND per pet, per day (Ready.gov / CDC / WA EMD). */
    public int waterGallons(Ctx c) {
        return (c.persons() + c.petsTotal()) * PLAN_DAYS;
    }

    /** Template row by key — the seeding + "Mark refreshed" lookup. */
    public Optional<TemplateItem> templateByKey(String itemKey) {
        if (itemKey == null) return Optional.empty();
        return Optional.ofNullable(TEMPLATE_BY_KEY.get(itemKey));
    }

    private static boolean applies(TemplateItem t, Ctx c) {
        String w = t.appliesWhen();
        if (w == null) return true;
        return switch (w) {
            case "infants"      -> c.infants() > 0;
            case "kids"         -> c.kids() > 0;
            case "pets"         -> c.petsTotal() > 0;
            case "seniorNeeds"  -> c.seniorNeeds();
            case "medicalNeeds" -> c.medicalNeeds();
            default             -> true;
        };
    }

    // ---------------------------------------------------------------------
    // Strategy meta (Buy / Build / Hybrid) — research doc §1–3
    // ---------------------------------------------------------------------

    private static final List<StrategyDto> STRATEGIES = List.of(
            new StrategyDto("hybrid",
                    "Buy a base kit, upgrade the weak spots",
                    "Instant coverage from a base kit, plus the seven things "
                            + "kits always skimp on: meds, a real water filter, a real "
                            + "headlamp and power bank, a NOAA radio, real first aid, "
                            + "extra calories, and your documents.",
                    "~$200–250/person",
                    List.of("gobag-kit-ready-america", "gobag-kit-sustain-essential-2"),
                    List.of("rx-meds", "water-filter", "flashlight", "power-bank",
                            "noaa-radio", "first-aid-kit", "food-ready-to-eat", "id-copies"),
                    true),
            new StrategyDto("premade",
                    "Buy a pre-made kit",
                    "Fastest path — one box covers the basics today. Heads up: "
                            + "most kits under-pack calories and first aid; we'll flag "
                            + "the gaps to fill.",
                    "~$50–130/person for a credible kit",
                    List.of("gobag-kit-ready-america", "gobag-kit-sustain-essential-2",
                            "gobag-kit-emergency-zone"),
                    List.of(),
                    false),
            new StrategyDto("diy",
                    "Build it yourself",
                    "Full control and the best quality per dollar. Your checklist "
                            + "below is sized to your household — check items off as "
                            + "you gather them.",
                    "~$220/person budget · ~$550 quality",
                    List.of(),
                    List.of(),
                    false));

    // ---------------------------------------------------------------------
    // THE baseline checklist template — research doc §6 (sources inline).
    // Priority: 0 = grab-list minimum (ARC "at a minimum"),
    //           1 = recommended, 2 = situational.
    // defaultShelfLifeDays is a SEED for the expiry date picker + the
    // "Mark refreshed" bump — items are only reminded once the user (or
    // the wizard's expiry step) actually dates them.
    // ---------------------------------------------------------------------

    private static final List<TemplateItem> TEMPLATE = List.of(
            // --- Water & Sustenance -------------------------------------
            new TemplateItem("water", "Emergency water (pouches or bottles)", "water", 0,
                    "gal", 1825,
                    "1 gallon per person per day for 3 days, pets included (Ready.gov, CDC; "
                            + "pet figure WA EMD). On foot? WA EMD's floor is 1 quart/person/day "
                            + "for drinking plus a filter.",
                    "gobag-water-pouches", null,
                    c -> (c.persons() + c.petsTotal()) * PLAN_DAYS),
            new TemplateItem("water-filter", "Portable water filter", "water", 1,
                    "count", null,
                    "A real filter (Sawyer Mini class) backs up carried water (WA EMD).",
                    "gobag-water-filter", null, c -> 1),
            new TemplateItem("water-tablets", "Water purification tablets", "water", 1,
                    "pack", 1825,
                    "The ultralight backup to the filter (CDC, WA EMD).",
                    "gobag-water-tablets", null, c -> 1),
            new TemplateItem("food-ready-to-eat", "Emergency food, no-cook (3-day supply each)", "water", 0,
                    "supplies", 1825,
                    "One 3-day ready-to-eat supply per person — ration bars or equivalent "
                            + "(Red Cross, Cal OES). Officials scale food in days, not calories.",
                    "gobag-ration-bars", null, Ctx::persons),
            new TemplateItem("can-opener", "Manual can opener", "water", 0,
                    "count", null,
                    "Needed if any canned food rides in the bag (Ready.gov).",
                    null, null, c -> 1),
            new TemplateItem("mess-kit", "Mess kit / cup / utensils", "water", 2,
                    "sets", null,
                    "One per person (Ready.gov).",
                    null, null, Ctx::persons),
            new TemplateItem("comfort-snacks", "Comfort / stress snacks", "water", 2,
                    "pack", 365,
                    "Familiar foods reduce stress, especially for kids (Ready.gov Food).",
                    null, null, c -> 1),

            // --- First Aid & Meds ---------------------------------------
            new TemplateItem("first-aid-kit", "First-aid kit (200+ piece class)", "firstaid", 0,
                    "count", 1095,
                    "A real kit, not a bandage pouch (Ready.gov, Red Cross).",
                    "gobag-first-aid-kit", null, c -> c.persons() > 4 ? 2 : 1),
            new TemplateItem("rx-meds", "Prescription meds (7–10 day supply each)", "firstaid", 0,
                    "supplies", 365,
                    "7–10 days per person in a waterproof, childproof container "
                            + "(Red Cross, CDC). Check each label's actual expiry.",
                    null, null, Ctx::persons),
            new TemplateItem("med-list", "Medication list", "firstaid", 0,
                    "count", 365,
                    "Every prescription: diagnosis, dosage, generic name, allergies (CDC).",
                    null, null, c -> 1),
            new TemplateItem("otc-meds", "OTC meds set", "firstaid", 1,
                    "set", 365,
                    "Pain/fever reliever, anti-diarrheal, antacid, antihistamine "
                            + "(Ready.gov, CDC).",
                    null, null, c -> 1),
            new TemplateItem("spare-glasses", "Spare glasses / contact solution", "firstaid", 1,
                    "set", null,
                    "Spare prescription eyewear per wearer (Ready.gov).",
                    null, null, c -> 1),
            new TemplateItem("meds-cooler", "Cooler + ice packs for refrigerated meds", "firstaid", 1,
                    "count", null,
                    "Chemical ice packs keep refrigerated meds viable (CDC, Ready.gov).",
                    null, "medicalNeeds", c -> 1),
            new TemplateItem("hearing-aid-batteries", "Hearing-aid batteries + device chargers", "firstaid", 1,
                    "set", 3650,
                    "Spare batteries + chargers for assistive devices (Ready.gov Older Adults).",
                    null, "seniorNeeds", c -> 1),
            new TemplateItem("device-backup-power", "Backup power for medical devices", "firstaid", 1,
                    "count", 180,
                    "Backup power + a list of device styles and serial numbers "
                            + "(Ready.gov Disabilities).",
                    null, "medicalNeeds", c -> 1),

            // --- Power, Light & Comms -----------------------------------
            new TemplateItem("flashlight", "Flashlight or headlamp", "power", 0,
                    "count", null,
                    "One per person (Ready.gov, Red Cross).",
                    "gobag-headlamp", null, Ctx::persons),
            new TemplateItem("batteries", "Extra batteries", "power", 0,
                    "sets", 3650,
                    "Store OUTSIDE devices; ~10-year shelf life (Red Cross).",
                    null, null, c -> Math.max(2, c.persons())),
            new TemplateItem("noaa-radio", "NOAA weather radio", "power", 0,
                    "count", null,
                    "Battery or hand-crank, with tone alert (Ready.gov, Red Cross).",
                    "gobag-noaa-radio", null, c -> 1),
            new TemplateItem("power-bank", "Phone power bank", "power", 0,
                    "count", 180,
                    "Recharge every 6 months — the clock-change check is the habit "
                            + "(Red Cross cadence).",
                    "gobag-power-bank", null, c -> Math.max(1, (c.persons() + 1) / 2)),
            new TemplateItem("phone-chargers", "Phone cables / chargers", "power", 0,
                    "set", null,
                    "Cables for every phone in the household (Ready.gov, Cal OES).",
                    null, null, c -> 1),
            new TemplateItem("whistle", "Whistle", "power", 0,
                    "count", null,
                    "Signal for help — three blasts (Ready.gov).",
                    null, null, Ctx::persons),
            new TemplateItem("light-sticks", "Light sticks", "power", 2,
                    "count", 1095,
                    "Snap lights — kid-safe ambient light (WA EMD).",
                    null, null, c -> c.persons() * 2),
            new TemplateItem("two-way-radios", "Two-way radios", "power", 2,
                    "pair", null,
                    "Family radios for when cell networks fail (Red Cross).",
                    null, null, c -> 1),

            // --- Shelter, Warmth & Clothing -----------------------------
            new TemplateItem("emergency-blanket", "Emergency blanket / bivvy", "shelter", 0,
                    "count", 1095,
                    "One per person; mylar folds degrade — inspect yearly "
                            + "(Ready.gov, Red Cross).",
                    "gobag-emergency-bivvy", null, Ctx::persons),
            new TemplateItem("clothes-change", "Change of clothes + sturdy shoes", "shelter", 0,
                    "sets", 180,
                    "Long sleeves, long pants, sturdy shoes per person; re-size kids "
                            + "at every 6-month check (Ready.gov).",
                    null, null, Ctx::persons),
            new TemplateItem("poncho", "Rain poncho", "shelter", 1,
                    "count", null,
                    "One per person (Red Cross).",
                    null, null, Ctx::persons),
            new TemplateItem("hat-gloves", "Hat + gloves", "shelter", 1,
                    "sets", null,
                    "One set per person (WA EMD).",
                    null, null, Ctx::persons),
            new TemplateItem("hand-warmers", "Hand warmers", "shelter", 2,
                    "pairs", 1095,
                    "Air-activated warmers (practical add; not an official-list item).",
                    "gobag-hand-warmers", null, c -> c.persons() * 2),
            new TemplateItem("shelter-sheeting", "Plastic sheeting + duct tape + scissors", "shelter", 2,
                    "set", null,
                    "Shelter-in-place seal kit (Ready.gov).",
                    null, null, c -> 1),
            new TemplateItem("matches-waterproof", "Matches in waterproof container", "shelter", 1,
                    "container", null,
                    "Waterproofed fire starting (Ready.gov, Red Cross).",
                    null, null, c -> 1),

            // --- Critical Documents & Cash ------------------------------
            new TemplateItem("id-copies", "ID copies (photo ID, birth cert, SS card)", "documents", 0,
                    "sets", 180,
                    "Copies, never originals — waterproof pouch; refresh every 6 months "
                            + "(Ready.gov, FEMA EFFAK, Be Ready Utah).",
                    null, null, Ctx::persons),
            new TemplateItem("insurance-copies", "Insurance + Medicare/Medicaid copies", "documents", 0,
                    "set", 180,
                    "Policies and cards (Ready.gov, Red Cross).",
                    null, null, c -> 1),
            new TemplateItem("financial-records", "Bank records + deed/lease copies", "documents", 1,
                    "set", 180,
                    "Proof of address, housing and income records (FEMA EFFAK, Red Cross).",
                    null, null, c -> 1),
            new TemplateItem("medical-records", "Immunization + physician records", "documents", 1,
                    "set", 180,
                    "Medical records and physician info (FEMA EFFAK, CDC).",
                    null, null, c -> 1),
            new TemplateItem("contact-cards", "Printed emergency-contact cards", "documents", 0,
                    "cards", 180,
                    "One in EVERY bag — including each kid's — with an out-of-state "
                            + "contact (Red Cross, WA EMD).",
                    null, null, Ctx::persons),
            new TemplateItem("cash-small-bills", "Cash in small bills", "documents", 0,
                    "stash", 365,
                    "Split into multiple stashes. No federal amount; Utah's EMA suggests "
                            + "~5 days of expenses (Be Ready Utah).",
                    null, null, c -> 1),
            new TemplateItem("paper-maps", "Local paper maps", "documents", 1,
                    "set", null,
                    "Navigation without a network (Ready.gov, Red Cross).",
                    null, null, c -> 1),
            new TemplateItem("family-photos", "Family photos for identification", "documents", 1,
                    "set", 365,
                    "Update yearly — especially young kids (Be Ready Utah).",
                    null, null, c -> 1),
            new TemplateItem("usb-document-drive", "Encrypted USB with document scans", "documents", 1,
                    "count", 180,
                    "Digital second set (FEMA EFFAK, Be Ready Utah).",
                    null, null, c -> 1),

            // --- Tools, Safety & Hygiene --------------------------------
            new TemplateItem("backpack", "The bag itself", "tools", 0,
                    "count", null,
                    "One sturdy pack per bag; store near your primary exit (WA EMD).",
                    "gobag-backpack", null, c -> 1),
            new TemplateItem("multi-tool", "Multi-purpose tool", "tools", 1,
                    "count", null,
                    "Multi-tool and/or knife (Red Cross, WA EMD).",
                    "gobag-multi-tool", null, c -> 1),
            new TemplateItem("utility-wrench", "Wrench/pliers for utility shutoff", "tools", 1,
                    "count", null,
                    "Non-sparking — home bag (Ready.gov).",
                    null, null, c -> 1),
            new TemplateItem("n95-masks", "N95 / dust masks", "tools", 0,
                    "count", 1825,
                    "Two per person (Ready.gov, Red Cross).",
                    null, null, c -> c.persons() * 2),
            new TemplateItem("sanitation-kit", "Sanitation kit (towelettes, bags, ties)", "tools", 0,
                    "kits", 1095,
                    "Moist towelettes, garbage bags, plastic ties (Ready.gov).",
                    null, null, Ctx::persons),
            new TemplateItem("hygiene-kit", "Personal hygiene kit", "tools", 0,
                    "kits", 1095,
                    "Soap, sanitizer, feminine supplies, toothbrush (Ready.gov).",
                    null, null, Ctx::persons),
            new TemplateItem("work-gloves", "Work gloves", "tools", 2,
                    "pairs", null,
                    "Debris handling (Red Cross hazard tier).",
                    null, null, c -> Math.max(1, c.adults() + c.teens())),
            new TemplateItem("spare-keys", "Spare house + car keys", "tools", 1,
                    "set", null,
                    "A full spare set (Red Cross, WA EMD).",
                    null, null, c -> 1),
            new TemplateItem("bleach-small", "Small unscented bleach (5–9%)", "tools", 2,
                    "bottle", 365,
                    "Doubles as a water disinfectant (CDC).",
                    null, null, c -> 1),
            new TemplateItem("paper-pencil", "Paper + pencil", "tools", 2,
                    "set", null,
                    "Notes and messages (Ready.gov).",
                    null, null, c -> 1),

            // --- Infants (appear when infants > 0) ----------------------
            new TemplateItem("infant-formula", "Ready-to-feed formula (3-day supply each)", "water", 0,
                    "supplies", 365,
                    "Ready-to-feed preferred; powder needs bottled water (Ready.gov Food).",
                    null, "infants", Ctx::infants),
            new TemplateItem("infant-bottles", "Bottles", "water", 0,
                    "count", null,
                    "Per infant (Red Cross).",
                    null, "infants", Ctx::infants),
            new TemplateItem("infant-diapers", "Diapers (3-day supply each)", "tools", 0,
                    "supplies", 180,
                    "Re-size at every 6-month check (Ready.gov).",
                    null, "infants", Ctx::infants),
            new TemplateItem("infant-wipes", "Wipes + changing supplies", "tools", 0,
                    "pack", 365,
                    "Wipes and changing supplies (Ready.gov).",
                    null, "infants", c -> 1),
            new TemplateItem("diaper-cream", "Diaper rash cream", "firstaid", 0,
                    "count", 1095,
                    "Per Ready.gov's infant list.",
                    null, "infants", c -> 1),

            // --- Kids (appear when kids > 0) ----------------------------
            new TemplateItem("kids-comfort-item", "Comfort item per child", "shelter", 1,
                    "count", null,
                    "Let kids help pack their own bag (WA EMD).",
                    null, "kids", Ctx::kids),
            new TemplateItem("kids-activities", "Non-electronic games / books", "shelter", 1,
                    "set", null,
                    "Games, books, puzzles (Ready.gov, Cal OES).",
                    null, "kids", c -> 1),

            // --- Pets (appear when petsTotal > 0) -----------------------
            new TemplateItem("pet-food", "Pet food (3-day supply each)", "water", 0,
                    "supplies", 365,
                    "Airtight, waterproof container (Ready.gov Pets). Pet water is "
                            + "already counted in the household water rule.",
                    null, "pets", Ctx::petsTotal),
            new TemplateItem("pet-meds", "Pet meds + dosing instructions", "firstaid", 1,
                    "supplies", 365,
                    "In a waterproof container (Ready.gov Pets).",
                    null, "pets", Ctx::petsTotal),
            new TemplateItem("pet-leash-backup", "Collar+ID + leash, PLUS backup set", "tools", 0,
                    "sets", null,
                    "A backup collar/ID/leash per pet (Ready.gov Pets).",
                    null, "pets", Ctx::petsTotal),
            new TemplateItem("pet-carrier", "Crate / carrier", "tools", 1,
                    "count", null,
                    "Ideally one per pet (Ready.gov, Red Cross).",
                    null, "pets", Ctx::petsTotal),
            new TemplateItem("pet-vax-records", "Vaccination / medical records", "documents", 0,
                    "sets", 365,
                    "Many emergency shelters require proof of current vaccination "
                            + "(Red Cross, Ready.gov).",
                    null, "pets", Ctx::petsTotal),
            new TemplateItem("pet-photo", "Photo of YOU with your pet", "documents", 0,
                    "count", 365,
                    "Proves ownership if you're separated (Ready.gov, Red Cross).",
                    null, "pets", Ctx::petsTotal),
            new TemplateItem("pet-sanitation", "Pet sanitation kit", "tools", 1,
                    "kit", null,
                    "Litter + box / waste bags, paper towels (Ready.gov Pets).",
                    null, "pets", c -> 1),
            new TemplateItem("pet-comfort", "Familiar toy / treats / bedding", "shelter", 2,
                    "items", null,
                    "Familiar items lower pet stress (Ready.gov Pets).",
                    null, "pets", Ctx::petsTotal),
            new TemplateItem("pet-boarding-card", "Pet boarding card (feeding, vet, behavior)", "documents", 1,
                    "cards", 180,
                    "For boarding or fostering if you're separated (Red Cross).",
                    null, "pets", Ctx::petsTotal)
    );

    private static final Map<String, TemplateItem> TEMPLATE_BY_KEY =
            TEMPLATE.stream().collect(Collectors.toUnmodifiableMap(TemplateItem::itemKey, t -> t));
}
