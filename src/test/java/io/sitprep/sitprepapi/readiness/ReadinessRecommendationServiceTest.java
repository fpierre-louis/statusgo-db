package io.sitprep.sitprepapi.readiness;

import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskDto;
import io.sitprep.sitprepapi.readiness.EssentialsReadinessService.EssentialsResult;
import io.sitprep.sitprepapi.readiness.ReadinessCatalog.CatalogItem;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.NextStepDto;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ranking is deterministic and explained by its largest factor
 * (CONTRACT.md §4). Expected picks below are worked by hand from the
 * catalog priorities so a weight change shows up as a failing test.
 */
class ReadinessRecommendationServiceTest {

    final ReadinessRecommendationService service = new ReadinessRecommendationService();
    static final EssentialsResult ALL_DONE = new EssentialsResult(true, true, true, true);

    static DerivedItem incomplete(CatalogItem item) {
        return new DerivedItem(item, CompletionState.INCOMPLETE, null, null, null, null, null, null);
    }

    static DerivedItem complete(CatalogItem item, Freshness f) {
        return new DerivedItem(item, CompletionState.COMPLETE, Instant.EPOCH, f, null, null, null, null);
    }

    /** Every always-applicable static item, incomplete. */
    static List<DerivedItem> freshHousehold() {
        List<DerivedItem> out = new ArrayList<>();
        for (CatalogItem i : ReadinessCatalog.staticItems()) {
            if (i.applicability() == ReadinessCatalog.Applicability.ALWAYS) out.add(incomplete(i));
        }
        return out;
    }

    static CatalogItem item(String key) { return ReadinessCatalog.staticItem(key).orElseThrow(); }

    static RiskDto risk(String hazard, String label, String tier) {
        return new RiskDto(hazard, label, tier, "r", "s");
    }

    @Test
    void freshHouseholdGetsTheOutOfAreaContactWithItsOwnUnfinishedReason() {
        NextStepDto next = service.recommend(JourneyMode.CALM, ALL_DONE, freshHousehold(), List.of());
        assertThat(next.itemKey()).isEqualTo("people.out_of_area_contact");
        assertThat(next.reason().code()).isEqualTo("UNFINISHED_AREA");
        assertThat(next.reason().text()).isEqualTo("Your household doesn't have an out-of-area contact yet.");
        assertThat(next.action().type()).isEqualTo(ReadinessAction.OPEN_EMERGENCY_CONTACTS);
        assertThat(next.action().params()).containsEntry("intent", "outOfTownContact");
    }

    @Test
    void unfinishedAreaAndGoodNextStepCarryTheStepsOwnWhy() {
        // Documents has nothing done: the folder is an unfinished area's first step.
        NextStepDto next = service.recommend(JourneyMode.CALM, ALL_DONE,
                List.of(incomplete(item("documents.first_folder"))), List.of());
        assertThat(next.reason().code()).isEqualTo("UNFINISHED_AREA");
        assertThat(next.reason().text())
                .isEqualTo("Keeping copies together makes them easier to grab, or to replace after an emergency.");

        // Documents already has a done step: same item, no factor → GOOD_NEXT_STEP, same sentence.
        next = service.recommend(JourneyMode.CALM, ALL_DONE, List.of(
                incomplete(item("documents.first_folder")),
                complete(item("documents.paper_numbers"), Freshness.CURRENT)), List.of());
        assertThat(next.itemKey()).isEqualTo("documents.first_folder");
        assertThat(next.reason().code()).isEqualTo("GOOD_NEXT_STEP");
        assertThat(next.reason().text())
                .isEqualTo("Keeping copies together makes them easier to grab, or to replace after an emergency.");

        next = service.recommend(JourneyMode.CALM, ALL_DONE, List.of(
                incomplete(item("outage.flashlight_bed")),
                complete(item("outage.charge_plan"), Freshness.CURRENT)), List.of());
        assertThat(next.reason().code()).isEqualTo("GOOD_NEXT_STEP");
        assertThat(next.reason().text())
                .isEqualTo("Outages often start at night, and a flashlight within reach is safer than a candle.");
    }

    @Test
    void localRiskStepsWithoutATierBoostNameTheirHazard() {
        var local = ReadinessCatalog.localRiskItems(ReadinessCatalogTest.profile(List.of(
                ReadinessCatalogTest.req("flood_sandbags", "flood", 3, "/ask", "risk_added"))));
        NextStepDto next = service.recommend(JourneyMode.CALM, ALL_DONE, List.of(incomplete(local.get(0))), List.of());
        assertThat(next.reason().code()).isEqualTo("UNFINISHED_AREA");
        assertThat(next.reason().text()).isEqualTo("Part of preparing for the flood risk where you live.");

        var heat = ReadinessCatalog.localRiskItems(ReadinessCatalogTest.profile(List.of(
                ReadinessCatalogTest.req("heat_cooling_plan", "extreme_heat", 3, "/ask", "risk_added"))));
        assertThat(heat.get(0).whyItMatters()).isEqualTo("Part of preparing for the extreme heat risk where you live.");
    }

    @Test
    void theDeterministicCodesStillWinOverTheWhy() {
        // A tier boost still says LOCAL_RISK, not the item's sentence.
        NextStepDto next = service.recommend(JourneyMode.CALM, ALL_DONE,
                List.of(incomplete(item("outage.flashlight_bed"))), List.of(risk("tornado", "Tornado", "high")));
        assertThat(next.reason().code()).isEqualTo("LOCAL_RISK");
        assertThat(next.reason().text()).isEqualTo("Suggested because your area has a tornado risk.");
    }

    @Test
    void deterministicRegardlessOfInputOrder() {
        List<DerivedItem> items = freshHousehold();
        String first = service.recommend(JourneyMode.CALM, ALL_DONE, items, List.of()).itemKey();
        for (long seed = 1; seed <= 5; seed++) {
            List<DerivedItem> shuffled = new ArrayList<>(items);
            Collections.shuffle(shuffled, new Random(seed));
            assertThat(service.recommend(JourneyMode.CALM, ALL_DONE, shuffled, List.of()).itemKey()).isEqualTo(first);
        }
    }

    @Test
    void localRiskBoostOutranksBasePriorityAndAreaOrder() {
        NextStepDto next = service.recommend(JourneyMode.CALM, ALL_DONE, freshHousehold(),
                List.of(risk("hurricane", "Hurricane", "high")));
        // flashlight_bed 82 + 30 beats first_folder 79 + 30 and out_of_area 92.
        assertThat(next.itemKey()).isEqualTo("outage.flashlight_bed");
        assertThat(next.reason().code()).isEqualTo("LOCAL_RISK");
        assertThat(next.reason().text()).isEqualTo("Suggested because your area has a hurricane risk.");
    }

    @Test
    void moderateTierBoostsLessAndArticleFollowsTheLabel() {
        // extreme heat moderate: warm_cool 63+15=78, flashlight 82+15=97 > out_of_area 92.
        NextStepDto next = service.recommend(JourneyMode.CALM, ALL_DONE, freshHousehold(),
                List.of(risk("extreme_heat", "Extreme heat", "moderate")));
        assertThat(next.itemKey()).isEqualTo("outage.flashlight_bed");
        assertThat(next.reason().code()).isEqualTo("LOCAL_RISK");
        assertThat(next.reason().text()).isEqualTo("Suggested because your area has an extreme heat risk.");
    }

    @Test
    void lowTierGivesNoBoost() {
        NextStepDto next = service.recommend(JourneyMode.CALM, ALL_DONE, freshHousehold(),
                List.of(risk("hurricane", "Hurricane", "low")));
        assertThat(next.itemKey()).isEqualTo("people.out_of_area_contact");
    }

    @Test
    void householdRelevanceRaisesThePetPlan() {
        List<DerivedItem> items = freshHousehold();
        items.add(incomplete(item("evacuation.pet_plan")));
        NextStepDto next = service.recommend(JourneyMode.CALM, ALL_DONE, items, List.of());
        assertThat(next.itemKey()).isEqualTo("evacuation.pet_plan");
        assertThat(next.reason().code()).isEqualTo("HOUSEHOLD_RELEVANCE");
        assertThat(next.reason().text()).isEqualTo("Suggested because your household includes pets.");
    }

    @Test
    void reviewDueItemIsACandidateAndSaysSo() {
        List<DerivedItem> items = new ArrayList<>();
        for (CatalogItem i : ReadinessCatalog.staticItems()) {
            if (i.applicability() != ReadinessCatalog.Applicability.ALWAYS) continue;
            items.add(i.key().equals("documents.printed_plan")
                    ? complete(i, Freshness.REVIEW_DUE) : complete(i, i.reviewAfterDays() == null ? null : Freshness.CURRENT));
        }
        NextStepDto next = service.recommend(JourneyMode.CALM, ALL_DONE, items, List.of());
        assertThat(next.itemKey()).isEqualTo("documents.printed_plan");
        assertThat(next.reason().code()).isEqualTo("REVIEW_DUE");
        assertThat(next.reason().text()).isEqualTo("It's been a while. A quick review keeps it current.");
    }

    @Test
    void reviewSoonIsNotACandidate() {
        List<DerivedItem> items = List.of(complete(item("documents.printed_plan"), Freshness.REVIEW_SOON));
        assertThat(service.recommend(JourneyMode.CALM, ALL_DONE, items, List.of())).isNull();
    }

    @Test
    void equalScoresBreakByCatalogIndex() {
        // local_risk.earthquake_secure_furniture: 59 + 30 + 12 + 4 + 5 + 2 = 112
        // outage.flashlight_bed (earthquake tag):  55 + 30 + 12 + 8 + 5 + 2 = 112 → index 3 < 1001.
        var local = ReadinessCatalog.localRiskItems(ReadinessCatalogTest.profile(List.of(
                ReadinessCatalogTest.req("earthquake_secure_furniture", "earthquake", 1, "/ask", "risk_added"))));
        List<DerivedItem> items = List.of(incomplete(local.get(0)), incomplete(item("outage.flashlight_bed")));
        NextStepDto next = service.recommend(JourneyMode.CALM, ALL_DONE, items,
                List.of(risk("earthquake", "Earthquake", "very_high")));
        assertThat(next.itemKey()).isEqualTo("outage.flashlight_bed");
    }

    @Test
    void equalScoreAndIndexBreakByKey() {
        var local = ReadinessCatalog.localRiskItems(ReadinessCatalogTest.profile(List.of(
                ReadinessCatalogTest.req("zeta_step", "flood", 5, "/ask", "risk_added"),
                ReadinessCatalogTest.req("alpha_step", "flood", 5, "/ask", "risk_added"))));
        List<DerivedItem> items = List.of(incomplete(local.get(0)), incomplete(local.get(1)));
        assertThat(service.recommend(JourneyMode.CALM, ALL_DONE, items, List.of()).itemKey())
                .isEqualTo("local_risk.alpha_step");
    }

    @Test
    void notRelevantAndSnoozedItemsAreNeverPicked() {
        CatalogItem out = item("people.out_of_area_contact");
        CatalogItem paper = item("documents.paper_numbers");
        CatalogItem folder = item("documents.first_folder");
        List<DerivedItem> items = List.of(
                new DerivedItem(out, CompletionState.INCOMPLETE, null, null, null, ItemStateKind.NOT_RELEVANT, null, null),
                new DerivedItem(folder, CompletionState.INCOMPLETE, null, null, null, null, ItemStateKind.SKIPPED, Instant.MAX),
                incomplete(paper));
        assertThat(service.recommend(JourneyMode.CALM, ALL_DONE, items, List.of()).itemKey())
                .isEqualTo("documents.paper_numbers");
    }

    @Test
    void nothingLeftMeansNoNextStep() {
        List<DerivedItem> items = List.of(complete(item("supplies.first_aid"), null));
        assertThat(service.recommend(JourneyMode.CALM, ALL_DONE, items, List.of())).isNull();
    }

    @Test
    void activeResponseSuppressesTheNextStep() {
        assertThat(service.recommend(JourneyMode.ACTIVE_RESPONSE, ALL_DONE, freshHousehold(), List.of())).isNull();
    }

    @Test
    void essentialsFirstReturnsHomesNextEssential() {
        var essentials = new EssentialsResult(true, false, false, true);
        NextStepDto next = service.recommend(JourneyMode.ESSENTIALS_FIRST, essentials, freshHousehold(), List.of());
        assertThat(next.itemKey()).isEqualTo("essentials.mealPlan");
        assertThat(next.title()).isEqualTo("Make a meal plan");
        assertThat(next.area()).isNull();
        assertThat(next.reason().code()).isEqualTo("ESSENTIALS_FIRST");
        assertThat(next.reason().text()).isEqualTo("Essentials come first. This is the next one.");
        assertThat(next.action().type()).isEqualTo(ReadinessAction.OPEN_ESSENTIALS);
        assertThat(next.action().params()).containsEntry("essentialKey", "mealPlan");
        assertThat(next.cost().band()).isEqualTo("FREE");
        assertThat(next.time().band()).isEqualTo("MIN_2");

        var evacNext = service.recommend(JourneyMode.ESSENTIALS_FIRST,
                new EssentialsResult(true, true, false, false), List.of(), List.of());
        assertThat(evacNext.itemKey()).isEqualTo("essentials.evacuation");
        assertThat(evacNext.title()).isEqualTo("Set an evacuation route and meeting place");
        assertThat(evacNext.time().band()).isEqualTo("MIN_5");
    }
}
