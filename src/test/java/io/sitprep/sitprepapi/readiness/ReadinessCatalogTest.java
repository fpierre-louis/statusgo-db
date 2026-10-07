package io.sitprep.sitprepapi.readiness;

import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskAdjustedRequirementDto;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskDto;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskProfileDto;
import io.sitprep.sitprepapi.readiness.ReadinessCatalog.CatalogItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalog is a persistence contract: {@code item_key} rows outlive any
 * deploy. These tests make a rename fail loudly instead of silently
 * orphaning every household's state rows.
 */
class ReadinessCatalogTest {

    /** EXACT ordered key list. Changing it is a data migration, not a refactor. */
    private static final List<String> STATIC_KEYS_SNAPSHOT = List.of(
            "documents.first_folder",
            "documents.paper_numbers",
            "documents.printed_plan",
            "outage.flashlight_bed",
            "outage.charge_plan",
            "outage.co_detector",
            "outage.food_safety",
            "outage.medical_backup",
            "outage.generator_safety",
            "outage.warm_cool_place",
            "supplies.light_batteries",
            "supplies.first_aid",
            "supplies.medication_buffer",
            "supplies.home_kit",
            "people.out_of_area_contact",
            "people.neighbor",
            "people.extended_family",
            "evacuation.alternate_route",
            "evacuation.offline_map",
            "evacuation.pet_plan",
            "practice.blackout_drill",
            "practice.contact_tree",
            "practice.plan_review");

    @Test
    void staticKeysMatchTheSnapshotInOrder() {
        assertThat(ReadinessCatalog.staticItems()).extracting(CatalogItem::key)
                .containsExactlyElementsOf(STATIC_KEYS_SNAPSHOT);
    }

    /**
     * Who may mark a MANUAL step done (EXEC-A1 task 4). Member-safe steps are
     * personal, physical and low-stakes; the rest describe shared household
     * records or decisions and stay with the admins. Widening this list is a
     * permission change — make it on purpose.
     */
    @Test
    void memberSafeManualStepsAreExactlyTheReviewedSix() {
        assertThat(ReadinessCatalog.staticItems().stream()
                .filter(i -> i.source() == CompletionSource.MANUAL && i.memberCanComplete())
                .map(CatalogItem::key))
                .containsExactlyInAnyOrder("outage.flashlight_bed", "outage.charge_plan", "outage.food_safety",
                        "outage.generator_safety", "documents.paper_numbers", "documents.printed_plan");
        assertThat(ReadinessCatalog.staticItems().stream()
                .filter(i -> i.source() == CompletionSource.MANUAL && !i.memberCanComplete())
                .map(CatalogItem::key))
                .containsExactlyInAnyOrder("documents.first_folder", "outage.medical_backup", "outage.warm_cool_place");
        RiskProfileDto profile = new RiskProfileDto("household_zip", "CO", "Colorado", List.of(),
                List.of(new RiskAdjustedRequirementDto("wildfire_defensible_space", "wildfire", "Clear a space",
                        "d", 1, "c", "/ask", "risk_added")), List.of(), Instant.now(), "v");
        assertThat(ReadinessCatalog.localRiskItems(profile)).singleElement()
                .satisfies(i -> assertThat(i.memberCanComplete()).isFalse());
    }

    /** Every step says why it matters, in its own words (EXEC-A1 task 5). */
    @Test
    void everyStaticStepHasItsOwnCalmWhy() {
        var seen = new HashSet<String>();
        for (CatalogItem item : ReadinessCatalog.staticItems()) {
            String why = item.whyItMatters();
            assertThat(why).as(item.key()).isNotBlank().endsWith(".");
            assertThat(seen.add(why)).as("duplicate why on %s", item.key()).isTrue();
            assertThat(why).as(item.key()).isNotEqualTo(item.description());
            // Surface copy rules (CONTRACT §9, CLAUDE.md practical preparedness).
            assertThat(why.toLowerCase()).as(item.key())
                    .doesNotContain("offline").doesNotContain("behind").doesNotContain("%")
                    .doesNotContain("good first step");
        }
    }

    @Test
    void keysAreUniqueAndWellFormed() {
        var seen = new HashSet<String>();
        for (CatalogItem item : ReadinessCatalog.staticItems()) {
            assertThat(seen.add(item.key())).as("duplicate key %s", item.key()).isTrue();
            assertThat(item.key()).matches(ReadinessCatalog.KEY_FORMAT);
            assertThat(item.key().length()).isLessThanOrEqualTo(96);
        }
    }

    @Test
    void catalogIndexIsDeclarationOrderAndEveryItemIsComplete() {
        List<CatalogItem> items = ReadinessCatalog.staticItems();
        for (int i = 0; i < items.size(); i++) {
            CatalogItem item = items.get(i);
            assertThat(item.catalogIndex()).isEqualTo(i);
            assertThat(item.title()).isNotBlank();
            assertThat(item.time()).isNotNull();
            assertThat(item.cost()).isNotNull();
            assertThat(item.effort()).isNotNull();
            assertThat(item.action()).isNotNull();
            assertThat(item.provenance()).isNotEmpty();
            assertThat(item.key()).startsWith(item.area().name().toLowerCase() + ".");
        }
    }

    @Test
    void govProvenanceCarriesOnlyFeCitedUrls() {
        var allowed = List.of("https://www.ready.gov/financial-preparedness",
                "https://www.ready.gov/power-outages", "https://www.ready.gov/heat");
        ReadinessCatalog.staticItems().forEach(i -> i.provenance().forEach(p -> {
            if (p.kind() == ProvenanceKind.READY_GOV || p.kind() == ProvenanceKind.CDC) {
                assertThat(p.url()).isIn(allowed);
            } else {
                assertThat(p.url()).isNull();
            }
        }));
    }

    @Test
    void contractPrioritiesAndSourcesHold() {
        Map<String, Integer> priorities = Map.of(
                "documents.first_folder", 60, "outage.medical_backup", 70,
                "people.out_of_area_contact", 65, "supplies.home_kit", 10, "evacuation.pet_plan", 56);
        priorities.forEach((k, p) -> assertThat(ReadinessCatalog.staticItem(k).orElseThrow().priority()).isEqualTo(p));
        assertThat(ReadinessCatalog.staticItem("outage.co_detector").orElseThrow().source())
                .isEqualTo(CompletionSource.STOCKPILE);
        assertThat(ReadinessCatalog.staticItem("practice.plan_review").orElseThrow().source())
                .isEqualTo(CompletionSource.PLAN_CONFIRMATION);
        assertThat(ReadinessCatalog.staticItem("outage.food_safety").orElseThrow().drillId())
                .isEqualTo("poweroutage-fridge-first-menu");
    }

    @Test
    void noCatalogCopyUsesTheWordOffline() {
        ReadinessCatalog.staticItems().forEach(i -> {
            assertThat(i.title().toLowerCase()).doesNotContain("offline");
            if (i.description() != null) assertThat(i.description().toLowerCase()).doesNotContain("offline");
        });
    }

    // ---------------------------------------------------------------- local risks

    static RiskProfileDto profile(List<RiskAdjustedRequirementDto> reqs) {
        return new RiskProfileDto("household_zip", "CA", "California",
                List.of(new RiskDto("earthquake", "Earthquake", "very_high", "r", "s")),
                reqs, List.of(), Instant.now(), "v");
    }

    static RiskAdjustedRequirementDto req(String key, String hazard, int priority, String route, String origin) {
        return new RiskAdjustedRequirementDto(key, hazard, key + " label", key + " detail", priority, "cta", route, origin);
    }

    @Test
    void localRisksKeepOnlyRiskAddedAndMapCompletionAndActions() {
        var items = ReadinessCatalog.localRiskItems(profile(List.of(
                req("active_alert_earthquake", "earthquake", 0, "/hazards", "active_alert_upgraded"),
                req("earthquake_utility_wrench", "earthquake", 1, "/go-bag", "risk_added"),
                req("earthquake_secure_furniture", "earthquake", 2, "/ask", "risk_added"),
                req("two_week_home_water", "earthquake", 4, "/create-foodsupply", "risk_added"),
                req("two_evacuation_routes", "wildfire", 14, "/evacuation-wizard", "risk_added"),
                req("earthquake_utility_wrench", "earthquake", 9, "/go-bag", "risk_added"))));

        assertThat(items).extracting(CatalogItem::key).containsExactly(
                "local_risk.earthquake_utility_wrench",
                "local_risk.earthquake_secure_furniture",
                "local_risk.two_week_home_water",
                "local_risk.two_evacuation_routes");

        CatalogItem wrench = items.get(0);
        assertThat(wrench.source()).isEqualTo(CompletionSource.STOCKPILE);
        assertThat(wrench.stockpileKeys()).containsExactly("stockpile-utility-shutoff-wrench");
        assertThat(wrench.action()).isEqualTo(ReadinessAction.OPEN_HOME_STOCKPILE);
        assertThat(wrench.cost()).isEqualTo(CostBand.OPTIONAL_PURCHASE);
        assertThat(wrench.priority()).isEqualTo(59);
        assertThat(wrench.catalogIndex()).isEqualTo(1001);
        assertThat(wrench.tags()).containsExactly("earthquake");
        assertThat(wrench.provenance()).extracting(ReadinessCatalog.Provenance::kind)
                .containsExactly(ProvenanceKind.LOCAL_RISK);

        CatalogItem furniture = items.get(1);
        assertThat(furniture.source()).isEqualTo(CompletionSource.MANUAL);
        assertThat(furniture.action()).isEqualTo(ReadinessAction.OPEN_HAZARD_GUIDE);
        assertThat(furniture.actionParams()).containsEntry("hazard", "earthquake");
        assertThat(furniture.cost()).isEqualTo(CostBand.USE_WHAT_YOU_HAVE);

        assertThat(items.get(2).action()).isEqualTo(ReadinessAction.OPEN_FOOD_PLAN);

        CatalogItem routes = items.get(3);
        assertThat(routes.source()).isEqualTo(CompletionSource.EVACUATION_METRIC);
        assertThat(routes.evacMetric()).isEqualTo("alternate_route");
        assertThat(routes.action()).isEqualTo(ReadinessAction.OPEN_EVACUATION_PLAN);
        assertThat(routes.priority()).isEqualTo(46);
    }

    @Test
    void priorityFloorIsTwenty() {
        var items = ReadinessCatalog.localRiskItems(profile(List.of(
                req("tornado_safe_room", "tornado", 55, "/evacuation-wizard", "risk_added"))));
        assertThat(items.get(0).priority()).isEqualTo(20);
    }

    @Test
    void unknownLocationYieldsNoLocalItems() {
        var unknown = new RiskProfileDto("unknown", null, "your area", List.of(),
                List.of(req("set_home_location", null, 0, "/household", "location_prompt")),
                List.of(), Instant.now(), "v");
        assertThat(ReadinessCatalog.locationKnown(unknown)).isFalse();
        assertThat(ReadinessCatalog.localRiskItems(unknown)).isEmpty();
        assertThat(ReadinessCatalog.locationKnown(null)).isFalse();
    }

    @Test
    void activeResponseToolsDropCommerceAndAlertSetup() {
        assertThat(ReadinessCatalog.tools(JourneyMode.CALM)).extracting(ReadinessCatalog.Tool::key)
                .containsExactly("tools.power_outage_playbook", "tools.alert_setup",
                        "tools.home_kit", "tools.emergency_contacts");
        assertThat(ReadinessCatalog.tools(JourneyMode.ACTIVE_RESPONSE)).extracting(ReadinessCatalog.Tool::key)
                .containsExactly("tools.power_outage_playbook", "tools.emergency_contacts");
    }

    @Test
    void timeBandNearestRoundsEssentialMinutes() {
        assertThat(TimeBand.nearest(2)).isEqualTo(TimeBand.MIN_2);
        assertThat(TimeBand.nearest(3)).isEqualTo(TimeBand.MIN_2);
        assertThat(TimeBand.nearest(5)).isEqualTo(TimeBand.MIN_5);
        assertThat(TimeBand.MIN_20_30.label()).isEqualTo("20–30 min");
    }
}
