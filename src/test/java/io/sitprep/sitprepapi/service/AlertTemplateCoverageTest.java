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

import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
                .getResourceAsStream("/fixtures/nws-alert-types-2026-08-27.json")) {
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

    @Test
    void everyProductionTemplateHasPass2SafetyMetadataOrABlockReason() throws Exception {
        Set<String> reviewStates = Set.of("draft", "source_verified", "blocked", "approved");
        Set<String> dispatchModes = Set.of("critical_push", "attention", "prepare", "feed", "suppress");
        Set<String> guidanceModes = Set.of("supplement_official", "official_only", "no_guidance");
        Set<String> allowedEvidenceSupports = Set.of(
                "eventAny", "incidentTypeAny", "_fallback",
                "body", "steps[0]", "steps[1]", "steps[2]",
                "askTag", "protectiveAction", "blockedReason",
                "sitprep.dispatchMode", "sitprep.guidanceMode",
                "sitprep.movementDirective", "sitprep.impactAware",
                "futureImpactNormalization");
        int count = 0;

        for (JsonNode node : productionTemplateNodes()) {
            count++;
            DispatchTemplate t = DispatchTemplate.fromJson(node);
            String name = templateName(node);

            assertThat(t.protectiveAction).as("%s protectiveAction", name).isNotNull();
            assertThat(t.compatibleResponseTypes).as("%s compatibleResponseTypes", name).isNotEmpty();
            assertThat(t.incompatibleResponseTypes).as("%s incompatibleResponseTypes", name).isNotEmpty();
            assertThat(t.sitprep).as("%s sitprep metadata", name).isNotNull();
            assertThat(t.sitprep.dispatchMode).as("%s dispatchMode", name).isIn(dispatchModes);
            assertThat(t.sitprep.guidanceMode).as("%s guidanceMode", name).isIn(guidanceModes);
            assertThat(t.sitprep.movementDirective)
                    .as("%s movementDirective", name)
                    .isIn("none", "evacuate", "shelter_in_place", "avoid_area", "follow_official_instruction");
            assertThat(t.safetyReview).as("%s safetyReview", name).isNotNull();
            assertThat(t.safetyReview.status).as("%s safetyReview.status", name).isIn(reviewStates);
            assertThat(t.safetyReview.version).as("%s safetyReview.version", name).isPositive();
            assertThat(t.safetyReview.sourceVerifiedAt)
                    .as("%s safetyReview.sourceVerifiedAt", name)
                    .isNotBlank();
            assertThat(t.safetyReview.approvedAt)
                    .as("%s safetyReview.approvedAt", name)
                    .isNull();

            boolean blocked = "blocked".equals(t.safetyReview.status);
            if (blocked) {
                assertThat(node.path("blockedReason").asText())
                        .as("%s blockedReason", name)
                        .isNotBlank();
            } else {
                assertThat(t.evidence).as("%s evidence", name).isNotEmpty();
            }
            for (AlertDispatchService.EvidenceMetadata evidence : t.evidence) {
                assertThat(evidence.supports).as("%s evidence supports for %s", name, evidence.title)
                        .isNotEmpty()
                        .allSatisfy(support -> assertThat(allowedEvidenceSupports)
                                .as("%s evidence support token %s", name, support)
                                .contains(support));
            }
        }

        assertThat(count).as("production template count after Pass 2B splits").isEqualTo(52);
    }

    @Test
    void productionTemplatesUseOnlyApprovedEvidenceHostsAndNoHumanApproval() throws Exception {
        for (JsonNode node : productionTemplateNodes()) {
            DispatchTemplate t = DispatchTemplate.fromJson(node);
            String name = templateName(node);
            assertThat(t.safetyReview.status)
                    .as("%s must not be marked human-approved by Pass 2", name)
                    .isNotEqualTo("approved");

            for (AlertDispatchService.EvidenceMetadata evidence : t.evidence) {
                assertThat(evidence.hostAllowed())
                        .as("%s evidence URL host is not explicitly allowed: %s", name, evidence.url)
                        .isTrue();
            }
        }
    }

    @Test
    void productionCompatibilityValuesAreKnownCapResponseValues() throws Exception {
        for (JsonNode node : productionTemplateNodes()) {
            DispatchTemplate t = DispatchTemplate.fromJson(node);
            for (String value : concat(t.compatibleResponseTypes, t.incompatibleResponseTypes)) {
                assertThat(AlertSafetyPolicy.actionFromResponse(value))
                        .as("%s uses unsupported responseType %s", templateName(node), value)
                        .isNotEqualTo(AlertSafetyPolicy.ProtectiveAction.UNKNOWN);
            }
        }
    }

    @Test
    void knownUnsafeGroupingsHaveDedicatedTemplatesNow() {
        DispatchTemplate tornado = dispatcher.matchForAlert(nws("Tornado Warning", "Extreme")).orElseThrow();
        DispatchTemplate extremeWind = dispatcher.matchForAlert(nws("Extreme Wind Warning", "Extreme")).orElseThrow();
        assertThat(extremeWind.headline).isEqualTo("Extreme wind warning");
        assertThat(extremeWind.body).doesNotContainIgnoringCase("tornado");
        assertThat(extremeWind).isNotSameAs(tornado);

        DispatchTemplate tropicalStorm = dispatcher.matchForAlert(nws("Tropical Storm Warning", "Severe")).orElseThrow();
        DispatchTemplate storm = dispatcher.matchForAlert(nws("Storm Warning", "Severe")).orElseThrow();
        assertThat(storm.headline).isEqualTo("Storm warning");
        assertThat(storm.body).doesNotContainIgnoringCase("tropical");
        assertThat(storm).isNotSameAs(tropicalStorm);

        DispatchTemplate winter = dispatcher.matchForAlert(nws("Winter Storm Warning", "Severe")).orElseThrow();
        DispatchTemplate snowSquall = dispatcher.matchForAlert(nws("Snow Squall Warning", "Severe")).orElseThrow();
        assertThat(snowSquall.headline).isEqualTo("Snow squall warning");
        assertThat(snowSquall.body).containsIgnoringCase("roads");
        assertThat(snowSquall).isNotSameAs(winter);

        DispatchTemplate tsunamiWatch = dispatcher.matchForAlert(nws("Tsunami Watch", "Severe")).orElseThrow();
        DispatchTemplate tsunamiAdvisory = dispatcher.matchForAlert(nws("Tsunami Advisory", "Severe")).orElseThrow();
        assertThat(tsunamiWatch.headline).isEqualTo("Tsunami watch");
        assertThat(tsunamiAdvisory.headline).isEqualTo("Tsunami advisory");
        assertThat(tsunamiAdvisory.protectiveAction).isEqualTo(AlertSafetyPolicy.ProtectiveAction.AVOID);
        assertThat(tsunamiAdvisory).isNotSameAs(tsunamiWatch);

        DispatchTemplate cold = dispatcher.matchForAlert(nws("Extreme Cold Watch", "Severe")).orElseThrow();
        DispatchTemplate freeze = dispatcher.matchForAlert(nws("Freeze Warning", "Severe")).orElseThrow();
        assertThat(cold.headline).isEqualTo("Extreme cold watch");
        assertThat(freeze.headline).isEqualTo("Freeze watch or warning");
        assertThat(cold).isNotSameAs(freeze);
    }

    @Test
    void knownUnsafeWordingDoesNotReturn() {
        DispatchTemplate thunderstorm = dispatcher
                .matchForAlert(nws("Severe Thunderstorm Warning", "Severe"))
                .orElseThrow();
        String thunderstormCopy = String.join(" ", thunderstorm.body, String.join(" ", thunderstorm.steps));
        assertThat(thunderstormCopy).doesNotContainIgnoringCase("unplug");
        assertThat(thunderstormCopy).doesNotContainIgnoringCase("damaging wind and hail are moving in");

        DispatchTemplate tornado = dispatcher.matchForAlert(nws("Tornado Warning", "Extreme")).orElseThrow();
        assertThat(tornado.body).doesNotContainIgnoringCase("spotted");
        assertThat(tornado.askTag).as("no tornado Ask guide exists yet").isNull();

        DispatchTemplate wind = dispatcher.matchForAlert(nws("Extreme Wind Warning", "Extreme")).orElseThrow();
        String windCopy = String.join(" ", wind.body, String.join(" ", wind.steps));
        assertThat(windCopy).doesNotContainIgnoringCase("basement");
        assertThat(windCopy).doesNotContainIgnoringCase("lowest floor");

        DispatchTemplate quake = dispatcher.matchForAlert(TestAlerts.usgs("M6.2 — Scotia Sea").build())
                .orElseThrow();
        assertThat(quake.headline).isEqualTo("Earthquake reported nearby");
        assertThat(quake.headline).doesNotContainIgnoringCase("felt");
    }

    @Test
    void extremeWindHasProductSpecificProvenanceWithoutTornadoShelterCopy() {
        DispatchTemplate wind = dispatcher.matchForAlert(nws("Extreme Wind Warning", "Extreme")).orElseThrow();
        String windCopy = String.join(" ", wind.body, String.join(" ", wind.steps));

        assertThat(wind.evidence)
                .anySatisfy(evidence -> {
                    assertThat(evidence.title).contains("Extreme Wind Warning");
                    assertThat(evidence.url).isEqualTo("https://www.weather.gov/wrn/wea360");
                    assertThat(evidence.supports).contains("eventAny", "sitprep.dispatchMode");
                });
        assertThat(windCopy).doesNotContainIgnoringCase("basement");
        assertThat(windCopy).doesNotContainIgnoringCase("lowest floor");
    }

    @Test
    void genericAirQualityDoesNotBorrowSmokeSpecificGuidance() {
        DispatchTemplate generic = dispatcher.matchForAlert(nws("Air Quality Alert", "Unknown")).orElseThrow();
        DispatchTemplate smoke = dispatcher.matchForAlert(nws("Dense Smoke Advisory", "Unknown")).orElseThrow();

        assertThat(generic).isNotSameAs(smoke);
        assertThat(generic.headline).isEqualTo("Air quality alert");
        assertThat(generic.askTag).isNull();
        assertThat(generic.sitprep.guidanceMode).isEqualTo("official_only");
        assertThat(generic.body).doesNotContainIgnoringCase("smoke").doesNotContainIgnoringCase("N95");
    }

    @Test
    void impactAwareTemplatesDefaultLowerUntilCapImpactDataEscalatesThem() {
        for (String event : List.of(
                "Severe Thunderstorm Warning",
                "Flash Flood Warning",
                "Flood Warning",
                "Snow Squall Warning")) {
            DispatchTemplate t = dispatcher.matchForAlert(nws(event, "Severe")).orElseThrow();
            assertThat(t.sitprep.impactAware).as("%s impact-aware", event).isTrue();
            assertThat(t.sitprep.dispatchMode).as("%s default dispatch", event).isEqualTo("attention");
        }
    }

    @Test
    void freezeWarningDoesNotBecomeCriticalBecauseItsNameSaysWarning() {
        NormalizedAlert freeze = TestAlerts.nws("Freeze Warning")
                .severity("Severe")
                .urgency("Immediate")
                .certainty("Likely")
                .responseTypes(List.of("Prepare"))
                .build();
        DispatchTemplate t = dispatcher.matchForAlert(freeze).orElseThrow();
        AlertSafetyPolicy.Decision decision = AlertSafetyPolicy.evaluate(freeze, t);

        assertThat(t.tier).isEqualTo("watch");
        assertThat(decision.dispatchMode()).isEqualTo(AlertSafetyPolicy.DispatchMode.PREPARE);
    }

    @Test
    void blockedCivilTemplatesRemainOfficialOnlyWithSemanticEvidence() {
        for (String event : List.of(
                "Civil Danger Warning",
                "Local Area Emergency",
                "Civil Emergency Message",
                "Law Enforcement Warning")) {
            DispatchTemplate t = dispatcher.matchForAlert(nws(event, "Severe")).orElseThrow();
            assertThat(t.safetyReview.status).as("%s review status", event).isEqualTo("blocked");
            assertThat(t.sitprep.guidanceMode).as("%s guidance", event).isEqualTo("official_only");
            assertThat(t.sitprep.movementDirective).as("%s movement directive", event)
                    .isEqualTo("follow_official_instruction");
            assertThat(t.evidence).as("%s semantic evidence", event).isNotEmpty();
        }
    }

    @Test
    void humanReviewMatrixMatchesProductionTemplates() throws Exception {
        String matrix = java.nio.file.Files.readString(
                java.nio.file.Path.of("docs/alerts/ALERT_TEMPLATE_HUMAN_REVIEW_MATRIX.md"),
                StandardCharsets.UTF_8);

        Pattern heading = Pattern.compile("^## \\d+\\. (.+)$", Pattern.MULTILINE);
        Matcher matcher = heading.matcher(matrix);
        List<String> matrixNames = new ArrayList<>();
        while (matcher.find()) matrixNames.add(matcher.group(1).trim());

        List<String> templateNames = productionTemplateNodes().stream()
                .map(AlertTemplateCoverageTest::templateName)
                .toList();

        assertThat(matrixNames).doesNotHaveDuplicates();
        assertThat(templateNames).doesNotHaveDuplicates();
        assertThat(matrixNames).containsExactlyElementsOf(templateNames);
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

    private static List<JsonNode> productionTemplateNodes() throws Exception {
        List<JsonNode> out = new ArrayList<>();
        try (InputStream in = AlertTemplateCoverageTest.class
                .getResourceAsStream("/templates/alert-dispatch-templates.json")) {
            assertThat(in).as("template resource present").isNotNull();
            for (JsonNode node : MAPPER.readTree(in).path("templates")) {
                if (node.isObject()) out.add(node);
            }
        }
        return out;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>();
        out.addAll(a == null ? List.of() : a);
        out.addAll(b == null ? List.of() : b);
        return out;
    }

    private static String templateName(JsonNode node) {
        if (node.has("eventAny") && node.path("eventAny").isArray()
                && node.path("eventAny").size() > 0) {
            List<String> events = new ArrayList<>();
            node.path("eventAny").forEach(event -> events.add(event.asText()));
            return String.join(" / ", events);
        }
        if (node.has("incidentTypeAny") && node.path("incidentTypeAny").isArray()) {
            List<String> types = new ArrayList<>();
            node.path("incidentTypeAny").forEach(type -> types.add(type.asText()));
            return "FEMA " + String.join(" / ", types);
        }
        if (node.path("_fallback").asBoolean(false)) return "FEMA fallback";
        return node.path("source").asText() + " " + node.path("headline").asText();
    }
}
