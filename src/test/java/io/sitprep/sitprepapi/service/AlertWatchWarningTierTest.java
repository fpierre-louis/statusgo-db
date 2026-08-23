package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.service.AlertDispatchService.DispatchTemplate;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Watch-vs-Warning and retraction guards (audit P0-3).
 *
 * <h2>The two bugs</h2>
 *
 * <p><b>1. Watches announced as warnings.</b> The dispatcher matched a
 * template's event word against the headline, so "Flood" matched "Flood
 * <i>Watch</i>" and 5 of the 26 matching alerts on the measured feed got
 * warning copy. A Flash Flood <i>Watch</i> would have pushed <i>"Flash flood
 * happening now."</i> Severity could not save it: <b>NWS rates a Flood Watch
 * "Severe" — the same value a Flood Warning carries.</b></p>
 *
 * <p><b>2. Alerts carrying their own retraction.</b> CAP's {@code response}
 * and {@code urgency} were dropped at the normalizer. On the measured feed
 * five alerts were retractions — three {@code AllClear} cancellations and two
 * supersessions — and one was an <b>Extreme Heat Warning reading "The Extreme
 * Heat Warning has been cancelled."</b> Once P0-2 made event matching exact,
 * that row matched warning-tier heat copy and would have pushed.</p>
 */
class AlertWatchWarningTierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static AlertDispatchService dispatcher;
    private static List<NormalizedAlert> liveFeed;

    @BeforeAll
    static void setUp() throws Exception {
        dispatcher = new AlertDispatchService(null, null, null, null, null, null, null, null);
        dispatcher.loadTemplates();

        AlertIngestService parseOnly = new AlertIngestService(new NwsZoneService());
        try (InputStream in = AlertWatchWarningTierTest.class
                .getResourceAsStream("/fixtures/nws-active-2026-08-22.json")) {
            JsonNode root = MAPPER.readTree(in);
            liveFeed = parseOnly.parseNwsFeed(root);
        }
    }

    private static NormalizedAlert alert(String event, String severity, String urgency,
                                         String certainty, String response) {
        return TestAlerts.nws(event)
                .severity(severity).urgency(urgency).certainty(certainty).response(response)
                .build();
    }

    // ==================================================================
    // The CAP fields are on the wire now
    // ==================================================================

    @Test
    void normalizerCarriesUrgencyCertaintyMessageTypeStatusAndResponse() {
        NormalizedAlert a = liveFeed.stream()
                .filter(x -> "Severe Thunderstorm Warning".equals(x.event()))
                .findFirst().orElseThrow();

        assertThat(a.urgency()).isEqualTo("Immediate");
        assertThat(a.certainty()).isEqualTo("Observed");
        assertThat(a.messageType()).isIn("Alert", "Update");
        assertThat(a.status()).isEqualTo("Actual");
        assertThat(a.response()).isEqualTo("Shelter");
    }

    // ==================================================================
    // 1. Watch never gets Warning copy — across every template
    // ==================================================================

    @Test
    void noWatchShapedAlertEverReachesWarningCopy_acrossEveryTemplate() {
        // Every Watch product SitPrep covers, in the CAP shape NWS actually
        // ships them in (Future / Possible), at the Severe severity that made
        // the old gate useless.
        List<String> watches = List.of(
                "Tornado Watch", "Severe Thunderstorm Watch", "Flash Flood Watch",
                "Flood Watch", "Hurricane Watch", "Typhoon Watch", "Tropical Storm Watch",
                "Storm Watch", "Winter Storm Watch", "Extreme Cold Watch",
                "Extreme Heat Watch", "Fire Weather Watch", "High Wind Watch",
                "Avalanche Watch", "Tsunami Watch", "Freeze Watch");

        for (String w : watches) {
            NormalizedAlert a = alert(w, "Severe", "Future", "Possible", "Prepare");
            DispatchTemplate t = dispatcher.matchForAlert(a)
                    .orElseThrow(() -> new AssertionError(w + " has no copy at all"));

            assertThat(t.isWarningTier())
                    .as("%s matched warning-tier copy \"%s\"", w, t.headline)
                    .isFalse();
            assertThat(AlertDispatchService.tierMatchesAlertShape(a, t))
                    .as("%s passed the shape cross-check as a warning", w)
                    .isTrue();   // watch copy on a watch is consistent
        }
    }

    @Test
    void severityCannotDistinguishThem_whichIsWhyTierExists() {
        // Both are "Severe". This is the whole reason the old gate failed.
        NormalizedAlert watch = alert("Flood Watch", "Severe", "Future", "Possible", "Prepare");
        NormalizedAlert warning = alert("Flood Warning", "Severe", "Immediate", "Observed", "Avoid");

        assertThat(watch.severity()).isEqualTo(warning.severity());

        assertThat(dispatcher.matchForAlert(watch).orElseThrow().isWarningTier()).isFalse();
        assertThat(dispatcher.matchForAlert(warning).orElseThrow().isWarningTier()).isTrue();
    }

    @Test
    void theExactBug_flashFloodWatchNeverSaysFlashFloodHappeningNow() {
        DispatchTemplate t = dispatcher
                .matchForAlert(alert("Flash Flood Watch", "Severe", "Future", "Possible", "Prepare"))
                .orElseThrow();
        assertThat(t.body).doesNotContain("happening now");
        assertThat(t.isWarningTier()).isFalse();
    }

    // ==================================================================
    // The shape cross-check — the guard on hand-edited config
    // ==================================================================

    @ParameterizedTest(name = "warning copy refused when urgency={0} certainty={1}")
    @CsvSource({
            "Future,   Possible",
            "Future,   Likely",
            "Expected, Possible",
    })
    void warningCopyIsRefusedOnAWatchShapedAlert(String urgency, String certainty) {
        // Simulates a hand-edit that mis-tiers a template: the alert is
        // watch-shaped but the copy claims warning tier.
        NormalizedAlert watchShaped = alert("Flood Warning", "Severe", urgency, certainty, "Avoid");
        DispatchTemplate warningCopy = dispatcher.matchForAlert(watchShaped).orElseThrow();

        assertThat(warningCopy.isWarningTier()).isTrue();
        assertThat(AlertDispatchService.tierMatchesAlertShape(watchShaped, warningCopy))
                .as("CAP says not-yet; the template says act-now. The cross-check must refuse.")
                .isFalse();
    }

    @ParameterizedTest(name = "warning copy allowed when urgency={0} certainty={1}")
    @CsvSource({
            "Immediate, Observed",
            "Immediate, Likely",
            "Expected,  Likely",
            "Expected,  Observed",
    })
    void warningCopyIsAllowedOnAGenuineWarningShape(String urgency, String certainty) {
        NormalizedAlert a = alert("Flood Warning", "Severe", urgency, certainty, "Avoid");
        assertThat(AlertDispatchService.tierMatchesAlertShape(a, dispatcher.matchForAlert(a).orElseThrow()))
                .isTrue();
    }

    // ==================================================================
    // 2. Retractions never dispatch
    // ==================================================================

    @Test
    void anAllClearNeverDispatches() {
        assertThat(AlertDispatchService.isStillInForce(
                alert("Extreme Heat Warning", "Severe", "Past", "Observed", "AllClear"))).isFalse();
    }

    @Test
    void aSupersededAlertNeverDispatches() {
        // "…has been replaced." response is Monitor, NOT AllClear — which is
        // why urgency is checked too rather than response alone.
        NormalizedAlert superseded =
                alert("Fire Weather Watch", "Severe", "Past", "Observed", "Monitor");
        assertThat(superseded.response()).isNotEqualTo("AllClear");
        assertThat(AlertDispatchService.isStillInForce(superseded)).isFalse();
    }

    @Test
    void aLiveWarningStillDispatches() {
        assertThat(AlertDispatchService.isStillInForce(
                alert("Flood Warning", "Severe", "Immediate", "Observed", "Avoid"))).isTrue();
    }

    @Test
    void everyRetractionInTheLiveFeedIsBlocked() {
        List<NormalizedAlert> retractions = liveFeed.stream()
                .filter(a -> a.headline() != null)
                .filter(a -> a.headline().toLowerCase().contains("cancelled")
                        || a.headline().toLowerCase().contains("has been replaced"))
                .toList();

        assertThat(retractions).as("retractions present in the captured feed").hasSize(5);
        assertThat(retractions).allSatisfy(a ->
                assertThat(AlertDispatchService.isStillInForce(a))
                        .as("would dispatch: %s", a.headline())
                        .isFalse());
    }

    @Test
    void theCancelledHeatWarningWouldOtherwiseHavePushed() {
        // Proves the gate is load-bearing rather than belt-and-braces: this
        // row matches warning-tier copy and is only stopped by isStillInForce.
        NormalizedAlert cancelled = liveFeed.stream()
                .filter(a -> "Extreme Heat Warning".equals(a.event()))
                .filter(a -> "AllClear".equalsIgnoreCase(a.response()))
                .findFirst().orElseThrow();

        assertThat(cancelled.headline()).contains("has been cancelled");
        assertThat(dispatcher.matchForAlert(cancelled).orElseThrow().isWarningTier())
                .as("it does match interruptive copy — the gate is what stops it")
                .isTrue();
        assertThat(AlertDispatchService.isStillInForce(cancelled)).isFalse();
    }

    // ==================================================================
    // 3. Test messages never reach the pipeline at all
    // ==================================================================

    @Test
    void nonActualMessagesAreDroppedAtIngest() {
        // The captured feed contains one `status: Test` row. `/alerts/active`
        // does not filter it out, and nothing downstream checked.
        assertThat(liveFeed).allSatisfy(a -> assertThat(a.status()).isEqualTo("Actual"));
    }

    @Test
    void theTestRowIsReallyInTheRawFeed_soTheFilterIsDoingWork() throws Exception {
        try (InputStream in = AlertWatchWarningTierTest.class
                .getResourceAsStream("/fixtures/nws-active-2026-08-22.json")) {
            JsonNode features = MAPPER.readTree(in).path("features");
            long testRows = 0;
            for (JsonNode f : features) {
                if (!"Actual".equals(f.path("properties").path("status").asText())) testRows++;
            }
            assertThat(testRows).as("non-Actual rows in the raw NWS response").isEqualTo(1);
        }
        // …and 310 raw features become 309 after the filter.
        assertThat(liveFeed).hasSize(309);
    }
}
