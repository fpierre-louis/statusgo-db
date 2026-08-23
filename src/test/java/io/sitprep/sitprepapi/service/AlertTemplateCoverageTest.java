package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.constant.HazardType;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.AlertDispatchService.DispatchTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dispatch-template coverage guards (audit P0-2, and the Watch/Warning half of
 * P0-3).
 *
 * <h2>What this file is for</h2>
 *
 * <p>NWS renamed "Excessive Heat Warning" to <b>"Extreme Heat Warning"</b>.
 * The template kept the old name, the matcher used a headline substring, and
 * <b>heat alerts went dark</b> — no auto-post, no push, no plain-language copy
 * — with nothing failing anywhere. Measured on the 2026-08-22 feed, only
 * <b>26 of 310 alerts (8.4%)</b> matched any template at all.</p>
 *
 * <p>So the contract these tests enforce is not "coverage is good today". It
 * is: <b>a product rename must fail here rather than ship silently.</b>
 * {@link #everyLifeSafetyProductHasCopy()} is the test that does that, and
 * {@link #declaredProductNamesAreRealNwsProducts()} is the one that catches a
 * typo in the other direction.</p>
 */
class AlertTemplateCoverageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static AlertDispatchService dispatcher;
    private static List<NormalizedAlert> liveFeed;

    /**
     * The NWS products SitPrep commits to having plain-language copy for.
     *
     * <p>This list is the actual deliverable of P0-2 — it is a product
     * decision written down, not a description of the code. Adding a name here
     * fails the build until copy exists for it; that is the intended
     * direction of causation.</p>
     *
     * <p>Advisories are deliberately absent (Heat Advisory, Wind Advisory,
     * Winter Weather Advisory, Dense Fog, Frost, ...). They are Attention-mode
     * per ALERTS_INTEGRATION.md, and a template would put them in the push
     * path. Marine products are absent for the same reason in reverse — 130 of
     * the 310 live alerts were Small Craft Advisories, and none of them are
     * about a household.</p>
     */
    private static final Set<String> LIFE_SAFETY_PRODUCTS = new LinkedHashSet<>(List.of(
            // Warning tier — act now
            "Tornado Warning", "Extreme Wind Warning",
            "Severe Thunderstorm Warning",
            "Flash Flood Warning", "Flash Flood Statement",
            "Flood Warning", "Flood Statement",
            "Hurricane Warning", "Typhoon Warning",
            "Tropical Storm Warning", "Storm Warning",
            "Blizzard Warning",
            "Winter Storm Warning", "Ice Storm Warning", "Snow Squall Warning",
            "Lake Effect Snow Warning",
            "Extreme Cold Warning",
            "Extreme Heat Warning",
            "Red Flag Warning", "Extreme Fire Danger", "Fire Warning",
            "High Wind Warning",
            "Dust Storm Warning", "Blowing Dust Warning",
            "Avalanche Warning",
            "Tsunami Warning",
            "Volcano Warning",
            "Earthquake Warning",
            "Air Quality Alert", "Dense Smoke Advisory",
            // Civil / non-weather emergencies relayed through NWS
            "Evacuation Immediate", "Shelter In Place Warning",
            "Civil Danger Warning", "Local Area Emergency",
            "Civil Emergency Message", "Law Enforcement Warning",
            "Hazardous Materials Warning", "Nuclear Power Plant Warning",
            "Radiological Hazard Warning",
            // Watch tier — get ready
            "Tornado Watch", "Severe Thunderstorm Watch",
            "Flash Flood Watch", "Flood Watch",
            "Hurricane Watch", "Typhoon Watch", "Tropical Storm Watch", "Storm Watch",
            "Winter Storm Watch",
            "Extreme Cold Watch", "Freeze Watch", "Freeze Warning",
            "Extreme Heat Watch",
            "Fire Weather Watch",
            "High Wind Watch",
            "Avalanche Watch",
            "Tsunami Watch", "Tsunami Advisory"));

    /**
     * Products whose names would match a Warning template under the old
     * substring rule. Each is a real bug that shipped.
     */
    private static final Set<String> WATCH_PRODUCTS = Set.of(
            "Tornado Watch", "Severe Thunderstorm Watch", "Flash Flood Watch",
            "Flood Watch", "Hurricane Watch", "Typhoon Watch",
            "Tropical Storm Watch", "Storm Watch", "Winter Storm Watch",
            "Extreme Cold Watch", "Extreme Heat Watch", "Fire Weather Watch",
            "High Wind Watch", "Avalanche Watch", "Tsunami Watch", "Freeze Watch");

    @BeforeAll
    static void setUp() throws Exception {
        dispatcher = new AlertDispatchService(null, null, null, null, null, null, null, null);
        dispatcher.loadTemplates();

        AlertIngestService parseOnly = new AlertIngestService(new NwsZoneService());
        try (InputStream in = AlertTemplateCoverageTest.class
                .getResourceAsStream("/fixtures/nws-active-2026-08-22.json")) {
            JsonNode root = MAPPER.readTree(in);
            liveFeed = parseOnly.parseNwsFeed(root);
        }
    }

    private static NormalizedAlert nws(String event, String severity) {
        return TestAlerts.nws(event).severity(severity).build();
    }

    // ==================================================================
    // The guard that would have caught the heat rename
    // ==================================================================

    @Test
    void everyLifeSafetyProductHasCopy() {
        List<String> uncovered = new ArrayList<>();
        for (String product : LIFE_SAFETY_PRODUCTS) {
            if (dispatcher.matchForAlert(nws(product, "Severe")).isEmpty()) {
                uncovered.add(product);
            }
        }
        assertThat(uncovered)
                .as("NWS products with no plain-language copy — a user gets raw "
                        + "CAP text, no auto-post and no push for each of these")
                .isEmpty();
    }

    @Test
    void theProductThatWentDark_extremeHeatWarning_isCoveredNow() {
        Optional<DispatchTemplate> t = dispatcher.matchForAlert(nws("Extreme Heat Warning", "Severe"));
        assertThat(t).isPresent();
        assertThat(t.get().headline).isEqualTo("Dangerous heat");
        assertThat(t.get().isWarningTier()).isTrue();
    }

    @Test
    void theRetiredProductName_excessiveHeatWarning_matchesNothing() {
        // If someone re-adds the old name "helpfully", this fails. NWS does not
        // issue it any more; a template for it is dead copy that hides the gap.
        assertThat(dispatcher.matchForAlert(nws("Excessive Heat Warning", "Severe")))
                .as("a product NWS no longer issues must not appear covered")
                .isEmpty();
    }

    @ParameterizedTest(name = "{0} now has copy")
    @ValueSource(strings = {
            "Red Flag Warning",           // 19 live, matched nothing before
            "Severe Thunderstorm Warning", // 9 live, no template existed at all
            "Extreme Heat Warning",        // 9 live, retired product name
            "Air Quality Alert",           // 8 live, severity "Unknown"
            "Evacuation Immediate"         // the one the audit said we had no source for
    })
    void productsThatPreviouslyMatchedNothing(String product) {
        assertThat(dispatcher.matchForAlert(nws(product, "Severe"))).isPresent();
    }

    @Test
    void declaredProductNamesAreRealNwsProducts() throws Exception {
        // Catches the inverse typo: copy authored for a product name that does
        // not exist, which looks like coverage and is not.
        Set<String> real;
        try (InputStream in = AlertTemplateCoverageTest.class
                .getResourceAsStream("/fixtures/nws-alert-types-2026-08-22.json")) {
            assertThat(in).as("NWS product-type fixture present").isNotNull();
            JsonNode types = MAPPER.readTree(in).path("eventTypes");
            real = new LinkedHashSet<>();
            types.forEach(n -> real.add(n.asText()));
        }
        assertThat(real).hasSizeGreaterThan(100);
        assertThat(LIFE_SAFETY_PRODUCTS)
                .as("every product we claim to cover must be one NWS actually issues")
                .allSatisfy(p -> assertThat(real).contains(p));
    }

    // ==================================================================
    // Watch vs Warning — the 19% that got the wrong copy
    // ==================================================================

    @Test
    void noWatchProductEverMatchesWarningTierCopy() {
        for (String watch : WATCH_PRODUCTS) {
            Optional<DispatchTemplate> t = dispatcher.matchForAlert(nws(watch, "Severe"));
            assertThat(t).as("%s should have watch copy", watch).isPresent();
            assertThat(t.get().isWarningTier())
                    .as("%s matched WARNING-tier copy \"%s\" — this is the bug where "
                            + "a Flash Flood Watch pushed \"Flash flood happening now\"",
                            watch, t.get().headline)
                    .isFalse();
        }
    }

    @Test
    void floodWatchNoLongerBorrowsFloodWarningCopy() {
        DispatchTemplate watch = dispatcher.matchForAlert(nws("Flood Watch", "Severe")).orElseThrow();
        DispatchTemplate warning = dispatcher.matchForAlert(nws("Flood Warning", "Severe")).orElseThrow();

        assertThat(watch.headline).isEqualTo("Flood watch");
        assertThat(warning.headline).isEqualTo("Flood warning");
        assertThat(watch.body).isNotEqualTo(warning.body);
        // The watch says "possible"; the warning says it is happening.
        assertThat(watch.body.toLowerCase(Locale.ROOT)).contains("possible");
        assertThat(warning.body.toLowerCase(Locale.ROOT)).contains("happening");
    }

    @Test
    void aWatchIsNeverPushWorthy_evenAtSevereSeverity() {
        // NWS rates Flood Watch "Severe" — the same value Flood Warning
        // carries. That is exactly why the old severity gate could not tell
        // them apart.
        DispatchTemplate t = dispatcher.matchForAlert(nws("Flood Watch", "Severe")).orElseThrow();
        assertThat(t.isWarningTier()).isFalse();
    }

    // ==================================================================
    // Copy quality — the templates ARE the user-facing text now
    // ==================================================================

    @Test
    void noTemplateShipsAnUnsubstitutedPlaceholder() {
        // Guards the {distance}mi {direction} class of defect (audit P0-5) at
        // the template level. The dispatch-time guarantee lands with P0-5.
        for (DispatchTemplate t : allTemplates()) {
            assertThat(t.body).doesNotContain("{distance}").doesNotContain("{direction}");
        }
    }

    @Test
    void everyTemplateHasCopyAndAKnownHazardType() {
        for (DispatchTemplate t : allTemplates()) {
            assertThat(t.headline).as("headline").isNotBlank();
            assertThat(t.body).as("body for %s", t.headline).isNotBlank();
            assertThat(t.tier).as("tier for %s", t.headline).isIn("warning", "watch");
            assertThat(HazardType.parse(t.hazardType))
                    .as("hazardType \"%s\" on \"%s\" is not in the HazardType vocabulary",
                            t.hazardType, t.headline)
                    .isPresent();
        }
    }

    @Test
    void copyStaysShortEnoughForAPushNotification() {
        // sendHazardAlertBatch truncates at 160 chars. A body longer than that
        // loses its last sentence on a lock screen — which is where the
        // instruction usually is.
        for (DispatchTemplate t : allTemplates()) {
            assertThat(t.body.length())
                    .as("\"%s\" body is %d chars and will be truncated in a push",
                            t.headline, t.body.length())
                    .isLessThanOrEqualTo(160);
        }
    }

    @Test
    void copyAvoidsTheJargonTheAuditFlagged() {
        List<String> banned = List.of(
                "relative humidity", "heat index", "precautionary", "preparedness actions",
                "NWS", "UGC", "CAP", "advisory is in effect", "hydrologic");
        for (DispatchTemplate t : allTemplates()) {
            String body = t.body.toLowerCase(Locale.ROOT);
            for (String term : banned) {
                assertThat(body)
                        .as("\"%s\" uses jargon term \"%s\"", t.headline, term)
                        .doesNotContain(term.toLowerCase(Locale.ROOT));
            }
        }
    }

    // ==================================================================
    // Against the real feed
    // ==================================================================

    @Test
    void coverageOfTheLiveFeedRoseFrom8PercentToTheWholeNonMarineSet() {
        List<NormalizedAlert> landAlerts = liveFeed.stream()
                .filter(a -> LIFE_SAFETY_PRODUCTS.contains(a.event()))
                .toList();

        assertThat(landAlerts)
                .as("life-safety alerts present in the captured feed")
                .isNotEmpty();

        List<String> missed = landAlerts.stream()
                .filter(a -> dispatcher.matchForAlert(a).isEmpty())
                .map(NormalizedAlert::event)
                .distinct()
                .toList();

        assertThat(missed).as("life-safety products in the live feed with no copy").isEmpty();
    }

    @Test
    void marineNoiseIsStillDeliberatelyUncovered() {
        // 130 of the 310 live alerts. Covering them would put "Small Craft
        // Advisory" in a household's feed, which is why the omission is a
        // decision rather than a gap.
        assertThat(dispatcher.matchForAlert(nws("Small Craft Advisory", "Minor"))).isEmpty();
        assertThat(dispatcher.matchForAlert(nws("Heat Advisory", "Moderate"))).isEmpty();
    }

    private static List<DispatchTemplate> allTemplates() {
        List<DispatchTemplate> out = new ArrayList<>();
        for (String p : LIFE_SAFETY_PRODUCTS) {
            dispatcher.matchForAlert(nws(p, "Severe")).ifPresent(out::add);
        }
        // USGS + FEMA aren't reachable through an NWS probe.
        dispatcher.matchForAlert(TestAlerts.usgs("M6.2 — Scotia Sea").build()).ifPresent(out::add);
        dispatcher.matchForAlert(TestAlerts.fema("Fire — SOME FIRE").build()).ifPresent(out::add);
        return out.stream().distinct().toList();
    }
}
