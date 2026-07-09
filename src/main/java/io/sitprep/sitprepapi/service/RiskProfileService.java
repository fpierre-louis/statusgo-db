package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.domain.UserSavedLocation;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskAdjustedRequirementDto;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskDto;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskProfileDto;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Location-Based Risk Engine (Phase 1 MVP). Resolves a household's home
 * location, maps it to recurring regional hazards via a STATIC rule catalog,
 * and appends hazard-specific requirements to the household readiness profile.
 *
 * <p><b>Resolution precedence</b> (first available signal wins):</p>
 * <ol>
 *   <li>{@code UserSavedLocation} where {@code isHome = true} (basis {@code saved_home})</li>
 *   <li>{@code Group.zipCode} (basis {@code household_zip})</li>
 *   <li>{@code UserInfo.lastKnownZip} (basis {@code last_known_zip})</li>
 *   <li>Fallback: {@code UNKNOWN} — national baseline + a top-priority "set your
 *       home location" CTA.</li>
 * </ol>
 *
 * <p><b>Not certification-grade.</b> This is a hand-authored state / zip-prefix
 * heuristic ({@link #DATASET_VERSION}). FEMA NRI/RAPT-derived hazard data is the
 * Phase 5 authoritative replacement — the DTO shape is designed to swap the
 * data source without a wire change.</p>
 */
@Service
public class RiskProfileService {

    static final String DATASET_VERSION = "mvp-static-heuristic-2026.07";
    private static final String SOURCE = "SitPrep MVP heuristic (state-level)";

    private final UserSavedLocationService savedLocationService;
    private final UserInfoRepo userInfoRepo;

    public RiskProfileService(UserSavedLocationService savedLocationService,
                              UserInfoRepo userInfoRepo) {
        this.savedLocationService = savedLocationService;
        this.userInfoRepo = userInfoRepo;
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public RiskProfileDto resolveFor(Group household) {
        if (household == null) return unknownProfile();
        String ownerEmail = household.getOwnerEmail();

        // 1) UserSavedLocation (isHome) — richest, reverse-geocoded source.
        if (ownerEmail != null && !ownerEmail.isBlank()) {
            Optional<UserSavedLocation> home = savedLocationService.homeFor(ownerEmail);
            if (home.isPresent()) {
                UserSavedLocation s = home.get();
                String code = stateCodeFromName(s.getState());
                if (code == null) code = zipToStateCode(s.getZipBucket());
                if (code != null || notBlank(s.getState()) || notBlank(s.getZipBucket())) {
                    return build("saved_home", code, s.getState());
                }
            }
        }

        // 2) Household zip.
        if (notBlank(household.getZipCode())) {
            return build("household_zip", zipToStateCode(household.getZipCode()), null);
        }

        // 3) Owner's last-known zip (live-location signal — lowest precedence).
        if (ownerEmail != null && !ownerEmail.isBlank()) {
            String lkz = userInfoRepo.findByUserEmailIgnoreCase(ownerEmail)
                    .map(UserInfo::getLastKnownZip).orElse(null);
            if (notBlank(lkz)) {
                return build("last_known_zip", zipToStateCode(lkz), null);
            }
        }

        // 4) Unknown.
        return unknownProfile();
    }

    // ---------------------------------------------------------------------
    // Rule engine
    // ---------------------------------------------------------------------

    private RiskProfileDto build(String basis, String stateCode, String rawStateName) {
        String regionLabel = stateCode != null
                ? STATE_CODE_TO_NAME.getOrDefault(stateCode, notBlank(rawStateName) ? rawStateName : "your area")
                : (notBlank(rawStateName) ? rawStateName : "your area");

        List<HazardTier> hazards = stateCode == null
                ? List.of() : STATE_HAZARDS.getOrDefault(stateCode, List.of());

        List<RiskDto> risks = new ArrayList<>();
        List<RiskAdjustedRequirementDto> reqs = new ArrayList<>();
        Set<String> seenReqKeys = new HashSet<>();

        hazards.stream()
                .sorted(Comparator.comparingInt(h -> tierRank(h.tier())))
                .forEach(h -> {
                    String label = HAZARD_LABEL.getOrDefault(h.hazard(), h.hazard());
                    risks.add(new RiskDto(h.hazard(), label, h.tier(),
                            String.format("%s is in a %s %s-risk area.",
                                    regionLabel, tierWord(h.tier()), label.toLowerCase(Locale.ROOT)),
                            SOURCE));
                    int base = tierRank(h.tier()) * 10;
                    List<Req> templates = HAZARD_REQUIREMENTS.getOrDefault(h.hazard(), List.of());
                    for (int i = 0; i < templates.size(); i++) {
                        Req r = templates.get(i);
                        if (!seenReqKeys.add(r.key())) continue; // dedupe across hazards
                        reqs.add(new RiskAdjustedRequirementDto(
                                r.key(), h.hazard(), r.label(), r.detail(),
                                base + i + 1, r.cta(), r.route(), "risk_added"));
                    }
                });

        reqs.sort(Comparator.comparingInt(RiskAdjustedRequirementDto::priority));
        return new RiskProfileDto(basis, stateCode, regionLabel,
                List.copyOf(risks), List.copyOf(reqs), Instant.now(), DATASET_VERSION);
    }

    private RiskProfileDto unknownProfile() {
        RiskAdjustedRequirementDto prompt = new RiskAdjustedRequirementDto(
                "set_home_location", null,
                "Set your home location to unlock localized hazard alerts",
                "We tailor your checklist to local risks — earthquakes, hurricanes, "
                        + "wildfires, winter storms — once we know your area.",
                0, "Set home location", "/household", "location_prompt");
        return new RiskProfileDto("unknown", null, "your area",
                List.of(), List.of(prompt), Instant.now(), DATASET_VERSION);
    }

    // ---------------------------------------------------------------------
    // Location helpers
    // ---------------------------------------------------------------------

    /** Full state name or 2-letter code → canonical 2-letter code, or null. */
    static String stateCodeFromName(String state) {
        if (!notBlank(state)) return null;
        String t = state.trim();
        if (t.length() == 2 && t.chars().allMatch(Character::isLetter)) {
            String code = t.toUpperCase(Locale.ROOT);
            return STATE_CODE_TO_NAME.containsKey(code) ? code : null;
        }
        return STATE_NAME_TO_CODE.get(t.toLowerCase(Locale.ROOT));
    }

    /** Zip (full or bucket) → state code via static prefix ranges, or null. */
    static String zipToStateCode(String zip) {
        if (!notBlank(zip)) return null;
        StringBuilder digits = new StringBuilder();
        for (char c : zip.toCharArray()) {
            if (Character.isDigit(c)) digits.append(c);
            if (digits.length() == 3) break;
        }
        if (digits.length() < 3) return null;
        int prefix = Integer.parseInt(digits.toString());
        for (ZipRange r : ZIP_RANGES) {
            if (prefix >= r.lo() && prefix <= r.hi()) return r.state();
        }
        return null;
    }

    private static int tierRank(String tier) {
        return switch (tier) {
            case "very_high" -> 0;
            case "high" -> 1;
            case "moderate" -> 2;
            default -> 3;
        };
    }

    private static String tierWord(String tier) {
        return switch (tier) {
            case "very_high" -> "very high";
            case "high" -> "high";
            case "moderate" -> "moderate";
            default -> "low";
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    // ---------------------------------------------------------------------
    // STATIC CATALOG (MVP — hand-authored, not certification-grade)
    // ---------------------------------------------------------------------

    private record HazardTier(String hazard, String tier) {}
    private record Req(String key, String label, String detail, String cta, String route) {}
    private record ZipRange(int lo, int hi, String state) {}

    private static HazardTier ht(String hazard, String tier) { return new HazardTier(hazard, tier); }

    private static final Map<String, String> HAZARD_LABEL = Map.of(
            "wildfire", "Wildfire",
            "earthquake", "Earthquake",
            "hurricane", "Hurricane",
            "flood", "Flood",
            "blizzard", "Winter storm",
            "extreme_heat", "Extreme heat",
            "tornado", "Tornado");

    /** State → recurring hazards + tiers. ~30 states; others = known-but-unmapped. */
    private static final Map<String, List<HazardTier>> STATE_HAZARDS = Map.ofEntries(
            Map.entry("CA", List.of(ht("earthquake", "very_high"), ht("wildfire", "high"))),
            Map.entry("AK", List.of(ht("earthquake", "very_high"))),
            Map.entry("WA", List.of(ht("earthquake", "moderate"), ht("wildfire", "moderate"))),
            Map.entry("OR", List.of(ht("wildfire", "high"), ht("earthquake", "moderate"))),
            Map.entry("NV", List.of(ht("extreme_heat", "high"), ht("earthquake", "moderate"))),
            Map.entry("AZ", List.of(ht("extreme_heat", "high"), ht("wildfire", "moderate"))),
            Map.entry("NM", List.of(ht("wildfire", "moderate"), ht("extreme_heat", "moderate"))),
            Map.entry("CO", List.of(ht("wildfire", "high"), ht("blizzard", "moderate"))),
            Map.entry("UT", List.of(ht("earthquake", "moderate"), ht("wildfire", "moderate"))),
            Map.entry("FL", List.of(ht("hurricane", "very_high"), ht("flood", "high"), ht("extreme_heat", "moderate"))),
            Map.entry("LA", List.of(ht("hurricane", "high"), ht("flood", "high"))),
            Map.entry("MS", List.of(ht("hurricane", "high"), ht("flood", "moderate"))),
            Map.entry("AL", List.of(ht("tornado", "high"), ht("hurricane", "moderate"))),
            Map.entry("GA", List.of(ht("hurricane", "moderate"), ht("flood", "moderate"))),
            Map.entry("SC", List.of(ht("hurricane", "high"), ht("flood", "moderate"))),
            Map.entry("NC", List.of(ht("hurricane", "high"), ht("flood", "moderate"))),
            Map.entry("TX", List.of(ht("tornado", "high"), ht("hurricane", "moderate"), ht("extreme_heat", "high"))),
            Map.entry("OK", List.of(ht("tornado", "very_high"))),
            Map.entry("KS", List.of(ht("tornado", "high"))),
            Map.entry("MO", List.of(ht("tornado", "moderate"), ht("flood", "moderate"))),
            Map.entry("KY", List.of(ht("tornado", "moderate"), ht("flood", "moderate"))),
            Map.entry("MN", List.of(ht("blizzard", "high"))),
            Map.entry("WI", List.of(ht("blizzard", "high"))),
            Map.entry("ND", List.of(ht("blizzard", "high"))),
            Map.entry("SD", List.of(ht("blizzard", "high"), ht("tornado", "moderate"))),
            Map.entry("MI", List.of(ht("blizzard", "high"))),
            Map.entry("NY", List.of(ht("blizzard", "high"))),
            Map.entry("MA", List.of(ht("blizzard", "high"))),
            Map.entry("PA", List.of(ht("blizzard", "moderate"), ht("flood", "moderate"))),
            Map.entry("HI", List.of(ht("hurricane", "moderate"), ht("flood", "moderate"))));

    /** Per-hazard risk-adjusted requirements (FEMA/Red Cross overlay rules). */
    private static final Map<String, List<Req>> HAZARD_REQUIREMENTS = Map.of(
            "earthquake", List.of(
                    new Req("earthquake_utility_wrench", "Utility shutoff wrench",
                            "Keep a wrench by the gas/water valves and know how to shut them off.",
                            "Add to go bag", "/go-bag"),
                    new Req("earthquake_secure_furniture", "Secure heavy furniture & water heater",
                            "Strap tall furniture, the water heater, and hanging items so they can't fall.",
                            "Review guidance", "/ask"),
                    new Req("earthquake_bed_kit", "Bedside kit — shoes, light, whistle",
                            "Sturdy shoes, a flashlight, and a whistle within reach of every bed.",
                            "Add to go bag", "/go-bag"),
                    new Req("two_week_home_water", "2-week water at home",
                            "Store 1 gallon per person per day for 14 days — quakes can cut mains.",
                            "Plan water", "/create-foodsupply")),
            "hurricane", List.of(
                    new Req("hurricane_inland_destination", "Inland / out-of-surge destination",
                            "Choose an evacuation destination outside the storm-surge zone.",
                            "Set meeting place", "/evacuation-wizard"),
                    new Req("waterproof_documents", "Waterproof document pouch",
                            "Seal IDs, insurance, and cash in a waterproof pouch.",
                            "Add to go bag", "/go-bag"),
                    new Req("noaa_radio", "Battery / hand-crank NOAA radio",
                            "A weather radio for alerts when power and cell service are down.",
                            "Add to go bag", "/go-bag"),
                    new Req("two_week_home_kit", "2-week home supply",
                            "Stock 14 days of food and water — resupply can be delayed after landfall.",
                            "Plan supplies", "/create-foodsupply"),
                    new Req("evacuation_trigger", "Evacuation trigger plan",
                            "Decide in advance which watch/warning level makes you leave.",
                            "Plan evacuation", "/evacuation-wizard")),
            "wildfire", List.of(
                    new Req("wildfire_respirators", "N95 / P100 respirators per person",
                            "Rated masks for wildfire smoke — one sized for each household member.",
                            "Add to go bag", "/go-bag"),
                    new Req("wildfire_clean_air_room", "Clean-air room plan",
                            "Pick a room you can seal and run an air filter in during heavy smoke.",
                            "Review guidance", "/ask"),
                    new Req("wildfire_go_bag_ready", "Go bag staged by the door",
                            "Keep the go bag packed and by the exit — wildfire evacuations move fast.",
                            "Open go bag", "/go-bag"),
                    new Req("two_evacuation_routes", "Two evacuation routes",
                            "Map a primary and an alternate way out in case one is cut off.",
                            "Set routes", "/evacuation-wizard")),
            "flood", List.of(
                    new Req("flood_higher_ground", "Higher-ground meeting place",
                            "Pick a meet-up spot on high ground away from the floodplain.",
                            "Set meeting place", "/evacuation-wizard"),
                    new Req("waterproof_documents", "Waterproof document storage",
                            "Store documents up high and sealed against water.",
                            "Add to go bag", "/go-bag"),
                    new Req("flood_route_avoidance", "Avoid-flooded-roads plan",
                            "Note which local roads flood first and how to route around them.",
                            "Review guidance", "/ask"),
                    new Req("flood_cleanup_ppe", "Cleanup PPE — gloves & boots",
                            "Waterproof gloves and boots for safe cleanup afterward.",
                            "Add to go bag", "/go-bag")),
            "blizzard", List.of(
                    new Req("blizzard_backup_heat", "Backup heat + CO detector",
                            "A safe backup heat source and a working carbon-monoxide alarm.",
                            "Review guidance", "/ask"),
                    new Req("blizzard_warm_gear", "Blankets & cold-weather gear",
                            "Extra blankets, layers, and hand warmers for a heat loss.",
                            "Add to go bag", "/go-bag"),
                    new Req("vehicle_winter_kit", "Vehicle winter kit",
                            "Blanket, sand or cat litter, scraper, and food/water in each car.",
                            "Add to go bag", "/go-bag"),
                    new Req("medication_continuity", "Medication continuity",
                            "Keep enough medication on hand to ride out days snowed in.",
                            "Review guidance", "/ask")),
            "extreme_heat", List.of(
                    new Req("heat_cooling_location", "Cooling location",
                            "Identify a nearby cooling center or air-conditioned place to go.",
                            "Set meeting place", "/evacuation-wizard"),
                    new Req("heat_hydration_plan", "Hydration + extra water",
                            "Extra drinking water and a plan to stay hydrated during heat waves.",
                            "Plan water", "/create-foodsupply"),
                    new Req("heat_power_backup", "Power-outage cooling backup",
                            "A plan for staying cool if the grid fails under peak demand.",
                            "Review guidance", "/ask")),
            "tornado", List.of(
                    new Req("tornado_safe_room", "Interior safe room",
                            "A lowest-floor interior room with no windows for tornado warnings.",
                            "Set meeting place", "/evacuation-wizard"),
                    new Req("tornado_head_protection", "Head protection — helmets",
                            "Bike or sports helmets cut head-injury risk from flying debris.",
                            "Add to go bag", "/go-bag"),
                    new Req("weather_radio_alerts", "Weather radio with alerts",
                            "A NOAA weather radio that wakes you for overnight warnings.",
                            "Add to go bag", "/go-bag")));

    private static final Map<String, String> STATE_CODE_TO_NAME = Map.ofEntries(
            Map.entry("AL", "Alabama"), Map.entry("AK", "Alaska"), Map.entry("AZ", "Arizona"),
            Map.entry("AR", "Arkansas"), Map.entry("CA", "California"), Map.entry("CO", "Colorado"),
            Map.entry("CT", "Connecticut"), Map.entry("DE", "Delaware"), Map.entry("FL", "Florida"),
            Map.entry("GA", "Georgia"), Map.entry("HI", "Hawaii"), Map.entry("ID", "Idaho"),
            Map.entry("IL", "Illinois"), Map.entry("IN", "Indiana"), Map.entry("IA", "Iowa"),
            Map.entry("KS", "Kansas"), Map.entry("KY", "Kentucky"), Map.entry("LA", "Louisiana"),
            Map.entry("ME", "Maine"), Map.entry("MD", "Maryland"), Map.entry("MA", "Massachusetts"),
            Map.entry("MI", "Michigan"), Map.entry("MN", "Minnesota"), Map.entry("MS", "Mississippi"),
            Map.entry("MO", "Missouri"), Map.entry("MT", "Montana"), Map.entry("NE", "Nebraska"),
            Map.entry("NV", "Nevada"), Map.entry("NH", "New Hampshire"), Map.entry("NJ", "New Jersey"),
            Map.entry("NM", "New Mexico"), Map.entry("NY", "New York"), Map.entry("NC", "North Carolina"),
            Map.entry("ND", "North Dakota"), Map.entry("OH", "Ohio"), Map.entry("OK", "Oklahoma"),
            Map.entry("OR", "Oregon"), Map.entry("PA", "Pennsylvania"), Map.entry("RI", "Rhode Island"),
            Map.entry("SC", "South Carolina"), Map.entry("SD", "South Dakota"), Map.entry("TN", "Tennessee"),
            Map.entry("TX", "Texas"), Map.entry("UT", "Utah"), Map.entry("VT", "Vermont"),
            Map.entry("VA", "Virginia"), Map.entry("WA", "Washington"), Map.entry("WV", "West Virginia"),
            Map.entry("WI", "Wisconsin"), Map.entry("WY", "Wyoming"), Map.entry("DC", "District of Columbia"));

    private static final Map<String, String> STATE_NAME_TO_CODE = STATE_CODE_TO_NAME.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(e -> e.getValue().toLowerCase(Locale.ROOT), Map.Entry::getKey));

    /** Zip 3-digit prefix ranges → state (coarse MVP heuristic). */
    private static final List<ZipRange> ZIP_RANGES = List.of(
            new ZipRange(10, 27, "MA"), new ZipRange(100, 149, "NY"), new ZipRange(150, 196, "PA"),
            new ZipRange(200, 205, "DC"), new ZipRange(270, 289, "NC"), new ZipRange(290, 299, "SC"),
            new ZipRange(300, 319, "GA"), new ZipRange(320, 349, "FL"), new ZipRange(350, 369, "AL"),
            new ZipRange(386, 397, "MS"), new ZipRange(400, 427, "KY"), new ZipRange(480, 499, "MI"),
            new ZipRange(530, 549, "WI"), new ZipRange(550, 567, "MN"), new ZipRange(570, 577, "SD"),
            new ZipRange(580, 588, "ND"), new ZipRange(630, 658, "MO"), new ZipRange(660, 679, "KS"),
            new ZipRange(700, 714, "LA"), new ZipRange(730, 749, "OK"), new ZipRange(750, 799, "TX"),
            new ZipRange(800, 816, "CO"), new ZipRange(840, 847, "UT"), new ZipRange(850, 865, "AZ"),
            new ZipRange(870, 884, "NM"), new ZipRange(889, 898, "NV"), new ZipRange(900, 961, "CA"),
            new ZipRange(967, 968, "HI"), new ZipRange(970, 979, "OR"), new ZipRange(980, 994, "WA"),
            new ZipRange(995, 999, "AK"));
}
