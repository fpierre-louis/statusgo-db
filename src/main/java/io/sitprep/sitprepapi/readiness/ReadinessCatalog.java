package io.sitprep.sitprepapi.readiness;

import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskAdjustedRequirementDto;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskProfileDto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * THE readiness catalog — stable item keys, areas, completion sources and
 * client metadata for "Ready for More" (CONTRACT.md §2).
 *
 * <p><b>Keys are a persistence contract.</b> {@code household_readiness_item_state.item_key}
 * stores them, so renaming one silently orphans every household's DONE /
 * NOT_RELEVANT / SKIPPED row. {@code ReadinessCatalogTest} snapshots the exact
 * ordered key list so a rename fails loudly; bump {@link #CATALOG_VERSION}
 * whenever behavior changes.</p>
 *
 * <p>Guidance lines are lifted from content the FE already ships
 * (PowerOutagePlaybook.jsx, challenges.js, the guides) or from the contract
 * text. Never invent safety guidance here.</p>
 */
public final class ReadinessCatalog {

    private ReadinessCatalog() {}

    public static final String CATALOG_VERSION = "readiness-catalog-2026.10.07";

    /** Shape every catalog key must match (also fits the 96-char column). */
    public static final Pattern KEY_FORMAT = Pattern.compile("^[a-z][a-z_]*\\.[a-z0-9_]{1,80}$");

    /** Prefix for dynamic items derived from the household's risk profile. */
    public static final String LOCAL_RISK_PREFIX = "local_risk.";

    /** Catalog index base for dynamic local-risk items (tie-breaker only). */
    static final int LOCAL_RISK_INDEX_BASE = 1000;

    static final String GOOD_NEXT_STEP_TEXT = "A small step that makes the basics easier to rely on.";

    // ------------------------------------------------------------------
    // Item model
    // ------------------------------------------------------------------

    /** Who a step applies to. */
    public enum Applicability { ALWAYS, PETS, MEDICAL }

    /** Contact rules for {@link CompletionSource#CONTACTS} items. */
    public enum ContactRule { OUT_OF_AREA, NEIGHBOR, EXTENDED_FAMILY, PET }

    public record Provenance(ProvenanceKind kind, String url) {}

    /**
     * One catalog step. Completion fields beyond {@code source} are read only
     * for the matching source: {@code stockpileKeys}/{@code stockpileFull} for
     * STOCKPILE, {@code evacMetric} for EVACUATION_METRIC, {@code drillId} for
     * DRILL_LOG (and, on a MANUAL item, a drill that also completes it),
     * {@code contactRule} for CONTACTS.
     */
    public record CatalogItem(
            String key,
            ReadinessArea area,
            ReadinessScope scope,
            String title,
            String description,
            List<String> guidance,
            CompletionSource source,
            List<String> stockpileKeys,
            boolean stockpileFull,
            String evacMetric,
            String drillId,
            ContactRule contactRule,
            TimeBand time,
            CostBand cost,
            EffortBand effort,
            int priority,
            Integer reviewAfterDays,
            List<String> tags,
            ReadinessAction action,
            Map<String, String> actionParams,
            List<Provenance> provenance,
            Applicability applicability,
            /** Shown as the reason when household relevance drives the pick. */
            String relevanceReason,
            /** Item-specific copy for the unfinished-area reason, or null. */
            String unfinishedAreaReason,
            int catalogIndex
    ) {
        public boolean isLocalRisk() { return key.startsWith(LOCAL_RISK_PREFIX); }
    }

    // ------------------------------------------------------------------
    // Sources already cited by the FE content system
    // ------------------------------------------------------------------

    static final String READY_FINANCIAL = "https://www.ready.gov/financial-preparedness";
    static final String READY_POWER = "https://www.ready.gov/power-outages";
    static final String READY_HEAT = "https://www.ready.gov/heat";

    private static Provenance readyGov(String url) { return new Provenance(ProvenanceKind.READY_GOV, url); }
    private static final Provenance SITPREP = new Provenance(ProvenanceKind.SITPREP, null);
    private static final Provenance HOUSEHOLD_PLAN = new Provenance(ProvenanceKind.HOUSEHOLD_PLAN, null);
    private static final Provenance LOCAL_RISK = new Provenance(ProvenanceKind.LOCAL_RISK, null);

    // ------------------------------------------------------------------
    // Static items — order = catalog order (final tie-breaker)
    // ------------------------------------------------------------------

    private static final List<CatalogItem> ITEMS;

    static {
        List<CatalogItem> items = new ArrayList<>();

        // DOCUMENTS
        b("documents.first_folder", ReadinessArea.DOCUMENTS, "Put IDs and insurance in one folder")
                .desc("One folder you can grab: copies of IDs, insurance cards, a prescription list, your lease or deed, and pet records.")
                .guidance("Copies of photo IDs for everyone",
                        "Insurance cards and policy numbers",
                        "A written list of prescriptions and doses",
                        "Lease or deed, and pet vaccination records",
                        "Keep originals you can't replace somewhere safe; the folder holds copies",
                        "Tell one other adult where the folder lives")
                .manual().bands(TimeBand.MIN_15, CostBand.FREE, EffortBand.ON_YOUR_OWN).priority(60).review(365)
                .tags("hurricane", "flood", "wildfire").action(ReadinessAction.START_SELF_REPORT_STEP)
                .provenance(readyGov(READY_FINANCIAL)).build(items);
        b("documents.paper_numbers", ReadinessArea.DOCUMENTS, "Write key phone numbers on paper")
                .desc("A phone with a dead battery can't show you anyone's number.")
                .guidance("Household members, your out-of-area contact, doctor, pharmacy, insurer, landlord or utility",
                        "Keep one copy in the folder and one in a wallet or bag")
                .manual().bands(TimeBand.MIN_5, CostBand.FREE, EffortBand.ON_YOUR_OWN).priority(45).review(365)
                .action(ReadinessAction.START_SELF_REPORT_STEP).provenance(SITPREP).build(items);
        b("documents.printed_plan", ReadinessArea.DOCUMENTS, "Print a copy of your plan")
                .desc("Plans should work even when phones don't.")
                .guidance("Put a copy on the fridge and another in each go-bag.")
                .manual().bands(TimeBand.MIN_5, CostBand.USE_WHAT_YOU_HAVE, EffortBand.ON_YOUR_OWN).priority(35).review(180)
                .action(ReadinessAction.OPEN_PRINTABLE_PLAN).provenance(HOUSEHOLD_PLAN).build(items);

        // OUTAGE
        b("outage.flashlight_bed", ReadinessArea.OUTAGE, "Keep a flashlight where you sleep")
                .desc("Outages often start at night. Use flashlights, not candles.")
                .guidance("Switch on every flashlight and lantern in the house. Replace the dead batteries now, not the night you need them.")
                .manual().bands(TimeBand.MIN_2, CostBand.USE_WHAT_YOU_HAVE, EffortBand.ON_YOUR_OWN).priority(55).review(180)
                .tags("blizzard", "hurricane", "tornado", "earthquake", "extreme_heat")
                .action(ReadinessAction.START_SELF_REPORT_STEP).provenance(readyGov(READY_POWER)).build(items);
        b("outage.charge_plan", ReadinessArea.OUTAGE, "Have a way to charge phones")
                .desc("A charged power bank, a car charger, or a place nearby you can charge.")
                .guidance("Charge power banks every few months")
                .manual().bands(TimeBand.MIN_5, CostBand.USE_WHAT_YOU_HAVE, EffortBand.ON_YOUR_OWN).priority(50).review(180)
                .tags("hurricane", "blizzard", "extreme_heat")
                .action(ReadinessAction.START_SELF_REPORT_STEP).provenance(readyGov(READY_POWER)).build(items);
        b("outage.co_detector", ReadinessArea.OUTAGE, "Have a working CO alarm")
                .desc("Battery or battery-backup carbon monoxide alarm near sleeping areas. Generators and alternate heat make CO a real outage risk.")
                .guidance("Battery-powered or with battery backup, on every level of the home and near sleeping areas.",
                        "If an alarm sounds, get everyone into fresh air and call for help from outside the building.")
                .stockpile("stockpile-co-detector").bands(TimeBand.MIN_5, CostBand.OPTIONAL_PURCHASE, EffortBand.ON_YOUR_OWN).priority(52)
                .tags("blizzard", "hurricane")
                .action(ReadinessAction.OPEN_HOME_STOCKPILE, "category", "power_heat").provenance(readyGov(READY_POWER)).build(items);
        b("outage.food_safety", ReadinessArea.OUTAGE, "Know the fridge and freezer rule")
                .desc("Unopened, a fridge stays safe about 4 hours and a full freezer about 48.")
                .guidance("Keep the doors shut. Unopened, a refrigerator holds temperature about four hours, a full freezer about 48, a half-full freezer about 24.",
                        "Decide what you are eating before you open the door.",
                        "Leave an appliance thermometer inside. Afterwards it tells you whether food stayed below 40°F.",
                        "When in doubt, throw it out.")
                .manual().alsoDrill("poweroutage-fridge-first-menu")
                .bands(TimeBand.MIN_5, CostBand.FREE, EffortBand.ON_YOUR_OWN).priority(40)
                .tags("hurricane", "blizzard", "extreme_heat")
                .action(ReadinessAction.OPEN_POWER_OUTAGE_PLAYBOOK, "section", "food").provenance(readyGov(READY_POWER)).build(items);
        b("outage.medical_backup", ReadinessArea.OUTAGE, "Plan for medical devices and cold medicines")
                .desc("Ask your utility about a medical-priority list and your pharmacist about temperature limits.")
                .guidance("Ask your utility about a medical-priority registry if anyone depends on powered equipment.",
                        "Ask your pharmacist in advance about temperature limits for refrigerated medication such as insulin.",
                        "Have a plan for charging medical devices that does not depend on the grid.")
                .manual().bands(TimeBand.MIN_10, CostBand.FREE, EffortBand.ON_YOUR_OWN).priority(70).review(365)
                .applicability(Applicability.MEDICAL,
                        "Suggested because someone in your household relies on power or refrigerated medicine.")
                .action(ReadinessAction.OPEN_POWER_OUTAGE_PLAYBOOK, "section", "medical").provenance(readyGov(READY_POWER)).build(items);
        b("outage.generator_safety", ReadinessArea.OUTAGE, "Know the generator rules")
                .desc("Outdoors only, twenty feet from the house, never into a wall outlet.")
                .guidance("A generator runs outdoors only, at least twenty feet from the house, exhaust pointed away from doors, windows, and vents.",
                        "Never in a garage, even with the door open.",
                        "Never plug a generator into a wall outlet.")
                .manual().bands(TimeBand.MIN_2, CostBand.FREE, EffortBand.ON_YOUR_OWN).priority(25)
                .action(ReadinessAction.OPEN_POWER_OUTAGE_PLAYBOOK, "section", "generator").provenance(readyGov(READY_POWER)).build(items);
        b("outage.warm_cool_place", ReadinessArea.OUTAGE, "Pick a place to warm up or cool down")
                .desc("A library, community center, or relative's home you could go to if the power is out for days.")
                .guidance("In hot weather, go to a cooling center, library, or shopping center rather than enduring it.",
                        "In cold weather, close off unused rooms and keep the household in one insulated space.")
                .manual().bands(TimeBand.MIN_5, CostBand.FREE, EffortBand.WITH_HOUSEHOLD).priority(38).review(365)
                .tags("extreme_heat", "blizzard")
                .action(ReadinessAction.START_SELF_REPORT_STEP).provenance(readyGov(READY_HEAT)).build(items);

        // SUPPLIES
        b("supplies.light_batteries", ReadinessArea.SUPPLIES, "Flashlights and spare batteries")
                .desc("One per person plus a spare. Never candles as the primary light.")
                .stockpile("stockpile-flashlights", "stockpile-batteries")
                .bands(TimeBand.MIN_5, CostBand.UNDER_10, EffortBand.ON_YOUR_OWN).priority(42)
                .action(ReadinessAction.OPEN_HOME_STOCKPILE, "category", "power_heat").provenance(SITPREP).build(items);
        b("supplies.first_aid", ReadinessArea.SUPPLIES, "A basic first aid kit")
                .stockpile("stockpile-first-aid-kit")
                .bands(TimeBand.MIN_10, CostBand.OPTIONAL_PURCHASE, EffortBand.ON_YOUR_OWN).priority(30)
                .action(ReadinessAction.OPEN_HOME_STOCKPILE, "category", "medical").provenance(SITPREP).build(items);
        b("supplies.medication_buffer", ReadinessArea.SUPPLIES, "A few extra days of medicine")
                .desc("Ask your pharmacist whether an early refill is possible.")
                .stockpile("stockpile-rx-14day")
                .bands(TimeBand.MIN_10, CostBand.FREE, EffortBand.ON_YOUR_OWN).priority(34)
                .action(ReadinessAction.OPEN_HOME_STOCKPILE, "category", "medical").provenance(SITPREP).build(items);
        b("supplies.home_kit", ReadinessArea.SUPPLIES, "Build the 14-day home kit over time")
                .desc("A shelf at a time. It never lowers your baseline readiness.")
                .stockpileFull()
                .bands(TimeBand.MIN_20_30, CostBand.OPTIONAL_PURCHASE, EffortBand.WITH_HOUSEHOLD).priority(10)
                .action(ReadinessAction.OPEN_HOME_STOCKPILE).provenance(SITPREP).build(items);

        // PEOPLE
        b("people.out_of_area_contact", ReadinessArea.PEOPLE, "Add a contact outside your area")
                .desc("One person far enough away that the same storm won't reach them.")
                .contacts(ContactRule.OUT_OF_AREA)
                .bands(TimeBand.MIN_5, CostBand.FREE, EffortBand.ON_YOUR_OWN).priority(65)
                .action(ReadinessAction.OPEN_EMERGENCY_CONTACTS, "intent", "outOfTownContact")
                .provenance(HOUSEHOLD_PLAN)
                .unfinishedAreaReason("Suggested because no one outside your area is on your contact list yet.")
                .build(items);
        b("people.neighbor", ReadinessArea.PEOPLE, "Trade numbers with a neighbor")
                .contacts(ContactRule.NEIGHBOR)
                .bands(TimeBand.MIN_10, CostBand.FREE, EffortBand.ON_YOUR_OWN).priority(44)
                .action(ReadinessAction.OPEN_EMERGENCY_CONTACTS, "intent", "neighborCoordination")
                .provenance(HOUSEHOLD_PLAN).build(items);
        b("people.extended_family", ReadinessArea.PEOPLE, "Add family or friends outside your home")
                .contacts(ContactRule.EXTENDED_FAMILY)
                .bands(TimeBand.MIN_5, CostBand.FREE, EffortBand.ON_YOUR_OWN).priority(32)
                .action(ReadinessAction.OPEN_EMERGENCY_CONTACTS, "intent", "extendedFamilyContacts")
                .provenance(HOUSEHOLD_PLAN).build(items);

        // EVACUATION
        b("evacuation.alternate_route", ReadinessArea.EVACUATION, "Add a second way out")
                .desc("A second way out if your primary road is blocked.")
                .evac("alternate_route")
                .bands(TimeBand.MIN_10, CostBand.FREE, EffortBand.ON_YOUR_OWN).priority(48)
                .tags("wildfire", "hurricane", "flood")
                .action(ReadinessAction.OPEN_EVACUATION_PLAN, "step", "routes").provenance(HOUSEHOLD_PLAN).build(items);
        b("evacuation.offline_map", ReadinessArea.EVACUATION, "Save your route in your maps app")
                .desc("Download it in Google or Apple Maps so it works with no cell signal.")
                .evac("offline_maps")
                .bands(TimeBand.MIN_5, CostBand.FREE, EffortBand.ON_YOUR_OWN).priority(36)
                .tags("wildfire", "hurricane")
                .action(ReadinessAction.OPEN_EVACUATION_PLAN, "step", "routes").provenance(HOUSEHOLD_PLAN).build(items);
        b("evacuation.pet_plan", ReadinessArea.EVACUATION, "Plan where your pets can go")
                .contacts(ContactRule.PET)
                .bands(TimeBand.MIN_10, CostBand.FREE, EffortBand.WITH_HOUSEHOLD).priority(56)
                .applicability(Applicability.PETS, "Suggested because your household includes pets.")
                .action(ReadinessAction.OPEN_EMERGENCY_CONTACTS, "intent", "petEvacuation")
                .provenance(HOUSEHOLD_PLAN).build(items);

        // PRACTICE
        b("practice.blackout_drill", ReadinessArea.PRACTICE, "Try a ten-minute blackout")
                .desc("Turn off one room's lights for ten minutes. Find chargers, flashlights, safe heating or cooling plans, and the food you would use first.")
                .drill("poweroutage-blackout-test")
                .bands(TimeBand.MIN_10, CostBand.FREE, EffortBand.WITH_HOUSEHOLD).priority(40).review(180)
                .tags("blizzard", "hurricane", "extreme_heat")
                .action(ReadinessAction.OPEN_DRILL, "drillId", "poweroutage-blackout-test")
                .provenance(readyGov(READY_POWER)).build(items);
        b("practice.contact_tree", ReadinessArea.PRACTICE, "Test your call chain")
                .desc("Send the real message to everyone on the list and see who answers.")
                .drill("contact-tree")
                .bands(TimeBand.MIN_10, CostBand.FREE, EffortBand.WITH_HOUSEHOLD).priority(33).review(180)
                .action(ReadinessAction.OPEN_DRILL, "drillId", "contact-tree")
                .provenance(HOUSEHOLD_PLAN).build(items);
        b("practice.plan_review", ReadinessArea.PRACTICE, "Review your plan together")
                .planConfirmation()
                .bands(TimeBand.MIN_10, CostBand.FREE, EffortBand.WITH_HOUSEHOLD).priority(28).review(180)
                .action(ReadinessAction.OPEN_HOUSEHOLD_PLAN).provenance(HOUSEHOLD_PLAN).build(items);

        ITEMS = Collections.unmodifiableList(items);
    }

    /** Static items in catalog order. */
    public static List<CatalogItem> staticItems() { return ITEMS; }

    public static Optional<CatalogItem> staticItem(String key) {
        if (key == null) return Optional.empty();
        return ITEMS.stream().filter(i -> i.key().equals(key)).findFirst();
    }

    // ------------------------------------------------------------------
    // Local risks — dynamic, from RiskProfileService.resolveFor(group)
    // ------------------------------------------------------------------

    static final String ORIGIN_RISK_ADDED = "risk_added";
    static final String ORIGIN_ACTIVE_ALERT = "active_alert_upgraded";
    static final String ORIGIN_LOCATION_PROMPT = "location_prompt";

    /** A requirement → stockpile/evac completion mapping (CONTRACT.md §2 LOCAL_RISKS). */
    private record LocalMapping(CompletionSource source, List<String> stockpileKeys, boolean stockpileFull,
                                String evacMetric, String category) {}

    private static final Map<String, LocalMapping> LOCAL_MAPPINGS = Map.of(
            "earthquake_utility_wrench", new LocalMapping(CompletionSource.STOCKPILE,
                    List.of("stockpile-utility-shutoff-wrench"), false, null, "tools_safety"),
            "noaa_radio", new LocalMapping(CompletionSource.STOCKPILE,
                    List.of("stockpile-noaa-radio"), false, null, "tools_safety"),
            "weather_radio_alerts", new LocalMapping(CompletionSource.STOCKPILE,
                    List.of("stockpile-noaa-radio"), false, null, "tools_safety"),
            "blizzard_backup_heat", new LocalMapping(CompletionSource.STOCKPILE,
                    List.of("stockpile-co-detector", "stockpile-warm-blankets"), false, null, "power_heat"),
            "two_evacuation_routes", new LocalMapping(CompletionSource.EVACUATION_METRIC,
                    List.of(), false, "alternate_route", null),
            "waterproof_documents", new LocalMapping(CompletionSource.STOCKPILE,
                    List.of("stockpile-document-binder"), false, null, "tools_safety"),
            "two_week_home_kit", new LocalMapping(CompletionSource.STOCKPILE,
                    List.of(), true, null, null));

    /** True when the risk profile knows the household's area. */
    public static boolean locationKnown(RiskProfileDto profile) {
        if (profile == null) return false;
        if (profile.locationBasis() == null || "unknown".equalsIgnoreCase(profile.locationBasis())) return false;
        List<RiskAdjustedRequirementDto> reqs = profile.riskAdjustedRequirements();
        return reqs == null || reqs.stream().noneMatch(r -> ORIGIN_LOCATION_PROMPT.equals(r.origin()));
    }

    /**
     * Local-risk items: only {@code origin == "risk_added"}. Active-alert
     * precautions belong to active response and location prompts are never
     * steps. Deduplicated by key, in the profile's priority order.
     */
    public static List<CatalogItem> localRiskItems(RiskProfileDto profile) {
        if (!locationKnown(profile) || profile.riskAdjustedRequirements() == null) return List.of();
        Map<String, CatalogItem> out = new LinkedHashMap<>();
        for (RiskAdjustedRequirementDto r : profile.riskAdjustedRequirements()) {
            if (r == null || r.key() == null || !ORIGIN_RISK_ADDED.equals(r.origin())) continue;
            String key = LOCAL_RISK_PREFIX + r.key();
            if (out.containsKey(key) || !KEY_FORMAT.matcher(key).matches()) continue;
            out.put(key, localRiskItem(r, key));
        }
        return List.copyOf(out.values());
    }

    private static CatalogItem localRiskItem(RiskAdjustedRequirementDto r, String key) {
        LocalMapping m = LOCAL_MAPPINGS.get(r.key());
        CompletionSource source = m == null ? CompletionSource.MANUAL : m.source();
        boolean purchase = m != null && m.source() == CompletionSource.STOCKPILE;

        ReadinessAction action;
        Map<String, String> params = new LinkedHashMap<>();
        if (purchase) {
            action = ReadinessAction.OPEN_HOME_STOCKPILE;
            if (m.category() != null) params.put("category", m.category());
        } else if ("/go-bag".equals(r.route())) {
            action = ReadinessAction.OPEN_GO_BAG;
        } else if ("/create-foodsupply".equals(r.route())) {
            action = ReadinessAction.OPEN_FOOD_PLAN;
        } else if ("/evacuation-wizard".equals(r.route())) {
            action = ReadinessAction.OPEN_EVACUATION_PLAN;
            if (m != null && m.source() == CompletionSource.EVACUATION_METRIC) params.put("step", "routes");
        } else {
            // "/ask", null, or anything unrecognized → the hazard guide.
            action = ReadinessAction.OPEN_HAZARD_GUIDE;
            if (r.hazard() != null) params.put("hazard", r.hazard());
        }

        return new CatalogItem(
                key, ReadinessArea.LOCAL_RISKS, ReadinessScope.HOUSEHOLD,
                r.label(), r.detail(), List.of(),
                source,
                m == null ? List.of() : m.stockpileKeys(),
                m != null && m.stockpileFull(),
                m == null ? null : m.evacMetric(),
                null, null,
                TimeBand.MIN_10,
                purchase ? CostBand.OPTIONAL_PURCHASE : CostBand.USE_WHAT_YOU_HAVE,
                EffortBand.ON_YOUR_OWN,
                Math.max(20, 60 - r.priority()),
                null,
                r.hazard() == null ? List.of() : List.of(r.hazard()),
                action, Collections.unmodifiableMap(params),
                List.of(LOCAL_RISK),
                Applicability.ALWAYS, null, null,
                LOCAL_RISK_INDEX_BASE + r.priority());
    }

    // ------------------------------------------------------------------
    // Useful tools (not items)
    // ------------------------------------------------------------------

    public record Tool(String key, String title, String description, ReadinessAction action,
                       boolean hiddenInActiveResponse) {}

    private static final List<Tool> TOOLS = List.of(
            new Tool("tools.power_outage_playbook", "Power outage playbook",
                    "What to do before, during, and after an outage.",
                    ReadinessAction.OPEN_POWER_OUTAGE_PLAYBOOK, false),
            new Tool("tools.alert_setup", "Alert setup",
                    "Choose which alerts reach you.",
                    ReadinessAction.OPEN_ALERT_PRESETS, true),
            new Tool("tools.home_kit", "14-day home kit",
                    "Track what you have at home for a longer stay.",
                    ReadinessAction.OPEN_HOME_STOCKPILE, true),
            new Tool("tools.emergency_contacts", "Emergency contacts",
                    "Your household's contact list.",
                    ReadinessAction.OPEN_EMERGENCY_CONTACTS, false));

    /** Tools for the mode; ACTIVE_RESPONSE drops commerce and alert setup. */
    public static List<Tool> tools(JourneyMode mode) {
        if (mode != JourneyMode.ACTIVE_RESPONSE) return TOOLS;
        return TOOLS.stream().filter(t -> !t.hiddenInActiveResponse()).toList();
    }

    // ------------------------------------------------------------------
    // Builder — keeps the static list above readable
    // ------------------------------------------------------------------

    private static Builder b(String key, ReadinessArea area, String title) {
        return new Builder(key, area, title);
    }

    private static final class Builder {
        private final String key;
        private final ReadinessArea area;
        private final String title;
        private String description;
        private List<String> guidance = List.of();
        private CompletionSource source = CompletionSource.MANUAL;
        private List<String> stockpileKeys = List.of();
        private boolean stockpileFull;
        private String evacMetric;
        private String drillId;
        private ContactRule contactRule;
        private TimeBand time;
        private CostBand cost;
        private EffortBand effort;
        private int priority;
        private Integer reviewAfterDays;
        private List<String> tags = List.of();
        private ReadinessAction action;
        private final Map<String, String> actionParams = new LinkedHashMap<>();
        private final Set<Provenance> provenance = new LinkedHashSet<>();
        private Applicability applicability = Applicability.ALWAYS;
        private String relevanceReason;
        private String unfinishedAreaReason;

        Builder(String key, ReadinessArea area, String title) {
            this.key = key;
            this.area = area;
            this.title = title;
        }

        Builder desc(String d) { this.description = d; return this; }
        Builder guidance(String... lines) { this.guidance = List.of(lines); return this; }
        Builder manual() { this.source = CompletionSource.MANUAL; return this; }
        Builder alsoDrill(String id) { this.drillId = id; return this; }
        Builder stockpile(String... keys) { this.source = CompletionSource.STOCKPILE; this.stockpileKeys = List.of(keys); return this; }
        Builder stockpileFull() { this.source = CompletionSource.STOCKPILE; this.stockpileFull = true; return this; }
        Builder evac(String metric) { this.source = CompletionSource.EVACUATION_METRIC; this.evacMetric = metric; return this; }
        Builder drill(String id) { this.source = CompletionSource.DRILL_LOG; this.drillId = id; return this; }
        Builder contacts(ContactRule rule) { this.source = CompletionSource.CONTACTS; this.contactRule = rule; return this; }
        Builder planConfirmation() { this.source = CompletionSource.PLAN_CONFIRMATION; return this; }
        Builder bands(TimeBand t, CostBand c, EffortBand e) { this.time = t; this.cost = c; this.effort = e; return this; }
        Builder priority(int p) { this.priority = p; return this; }
        Builder review(int days) { this.reviewAfterDays = days; return this; }
        Builder tags(String... t) { this.tags = List.of(t); return this; }
        Builder action(ReadinessAction a) { this.action = a; return this; }
        Builder action(ReadinessAction a, String param, String value) { this.action = a; this.actionParams.put(param, value); return this; }
        Builder provenance(Provenance p) { this.provenance.add(p); return this; }
        Builder applicability(Applicability a, String reason) { this.applicability = a; this.relevanceReason = reason; return this; }
        Builder unfinishedAreaReason(String r) { this.unfinishedAreaReason = r; return this; }

        void build(List<CatalogItem> into) {
            into.add(new CatalogItem(key, area, ReadinessScope.HOUSEHOLD, title, description, guidance,
                    source, stockpileKeys, stockpileFull, evacMetric, drillId, contactRule,
                    time, cost, effort, priority, reviewAfterDays, tags, action,
                    Collections.unmodifiableMap(new LinkedHashMap<>(actionParams)),
                    List.copyOf(provenance), applicability, relevanceReason, unfinishedAreaReason,
                    into.size()));
        }
    }
}
