package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.service.AlertDispatchService.DispatchTemplate;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards for audit P0-5 — unresolved template slots reaching users.
 *
 * <h2>The bug</h2>
 *
 * <p>{@code fillBody} substituted {@code {mag}} and left
 * {@code {distance}} / {@code {direction}} alone, documenting the choice as
 * "not computed in v1". So the literal string
 * <b>{@code "M6.2 earthquake about {distance}mi {direction}."}</b> was the body
 * of an APNs time-sensitive push — the notification class that breaks through
 * Focus modes.</p>
 *
 * <h2>Why they were removed rather than computed</h2>
 *
 * <p>Distance is a property of an (alert, recipient) pair. This body is built
 * <b>once</b> and handed to {@code sendHazardAlertBatch} as a single FCM
 * MulticastMessage for up to 500 recipients. Personalising it means abandoning
 * the batch for N sequential sends inside a transaction — a real regression in
 * the life-safety path, to gain a number the alert already expresses better as
 * {@code {place}} ("14 km E of Encinitas, CA").</p>
 *
 * <h2>What the tests enforce</h2>
 *
 * <p>Not "the templates are clean" — {@code AlertTemplateCoverageTest} does
 * that. These enforce that <b>an unresolved slot cannot reach a user even if a
 * template reintroduces one</b>, because that file is hand-edited without a
 * redeploy.</p>
 */
class AlertBodySlotTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static AlertDispatchService dispatcher;
    private static List<NormalizedAlert> liveFeed;

    @BeforeAll
    static void setUp() throws Exception {
        dispatcher = new AlertDispatchService(null, null, null, null, null, null, null, null);
        dispatcher.loadTemplates();

        AlertIngestService parseOnly = new AlertIngestService(new NwsZoneService());
        try (InputStream in = AlertBodySlotTest.class
                .getResourceAsStream("/fixtures/nws-active-2026-08-22.json")) {
            JsonNode root = MAPPER.readTree(in);
            liveFeed = parseOnly.parseNwsFeed(root);
        }
    }

    // ==================================================================
    // The acceptance criterion, stated literally
    // ==================================================================

    @Test
    void noDispatchedBodyContainsABrace_acrossEveryTemplateInTheLiveFeed() {
        int checked = 0;
        for (NormalizedAlert a : liveFeed) {
            DispatchTemplate t = dispatcher.matchForAlert(a).orElse(null);
            if (t == null) continue;
            checked++;
            String body = AlertDispatchService.fillBody(t, a);
            assertThat(body)
                    .as("dispatched body for \"%s\"", a.event())
                    .doesNotContain("{")
                    .doesNotContain("}");
        }
        assertThat(checked).as("alerts actually exercised").isGreaterThan(50);
    }

    @Test
    void theOriginalDefect_usgsPushNoLongerShipsDistanceAndDirection() {
        NormalizedAlert quake = TestAlerts.usgs("M6.2 — 14 km E of Encinitas, CA")
                .area("14 km E of Encinitas, CA").build();
        DispatchTemplate t = dispatcher.matchForAlert(quake).orElseThrow();

        String body = AlertDispatchService.fillBody(t, quake);
        assertThat(body).doesNotContain("{distance}").doesNotContain("{direction}");
        assertThat(body).isEqualTo(
                "A magnitude 6.2 earthquake was reported near 14 km E of Encinitas, CA. "
                        + "Check people first, then check for hazards.");
    }

    @ParameterizedTest(name = "USGS place shape: {0}")
    @ValueSource(strings = {
            "14 km E of Encinitas, CA",   // prepositional phrase
            "Scotia Sea",                  // bare region name
            "7 km NNW of Ridgecrest, California",
    })
    void placeReadsCorrectlyForBothUsgsLocationShapes(String place) {
        // USGS `place` is sometimes "N km DIR of X" and sometimes a bare region.
        // The copy names USGS's place string without inventing a recipient
        // distance or direction from the batched push path.
        NormalizedAlert quake = TestAlerts.usgs("M5.6 — " + place).area(place).build();
        String body = AlertDispatchService.fillBody(
                dispatcher.matchForAlert(quake).orElseThrow(), quake);

        assertThat(body).startsWith("A magnitude 5.6 earthquake was reported near " + place + ".");
        assertThat(body).doesNotContain("{");
    }

    // ==================================================================
    // The sanitizer — the guard on hand-edited config
    // ==================================================================

    @Test
    void anUnresolvedSlotDropsItsWholeSentence_notJustTheToken() {
        // Deleting the token alone leaves "about mi ." which reads as a
        // rendering bug and is arguably worse than the brace.
        String out = AlertDispatchService.sanitizeSlots(
                "M6.2 earthquake about {distance}mi {direction}. Check for gas leaks.",
                "fallback headline");

        assertThat(out).isEqualTo("Check for gas leaks.");
    }

    @Test
    void everySentenceLost_fallsBackToOurOwnPlainHeadline() {
        String out = AlertDispatchService.sanitizeSlots("{everything} is {broken}.", "Flood warning");
        assertThat(out).isEqualTo("Flood warning");
    }

    @Test
    void theFallbackIsNeverTheRawNwsWireHeadline() {
        // The first version of sanitizeSlots fell back to
        // NormalizedAlert.headline(). That string is the raw wire text, so the
        // safety net for a broken template was to reintroduce the issuing
        // office and two absolute timestamps — exactly the jargon P0-1..P0-3
        // exist to remove, just on the rare path instead of the common one.
        String rawWire = "Extreme Heat Warning issued August 22 at 11:42AM MST "
                + "until August 29 at 8:00PM MST by NWS Phoenix AZ";
        NormalizedAlert a = TestAlerts.nws("Extreme Heat Warning").headline(rawWire).build();
        DispatchTemplate t = dispatcher.matchForAlert(a).orElseThrow();

        // A body whose every sentence carries an unresolved slot.
        String body = AlertDispatchService.fillBody("{a} and {b}.", a, t.headline);

        assertThat(body).isEqualTo("Dangerous heat");
        assertThat(body)
                .doesNotContain("issued August")
                .doesNotContain("by NWS")
                .doesNotContain("MST");
    }

    @Test
    void everyTemplateHeadlineIsSafeToFallBackTo() {
        // The fallback is only as good as the string it lands on, so hold the
        // template headlines to the same bar as the bodies.
        for (String product : List.of("Extreme Heat Warning", "Red Flag Warning",
                "Tornado Warning", "Evacuation Immediate", "Flood Watch")) {
            String h = dispatcher.matchForAlert(TestAlerts.nws(product).build())
                    .orElseThrow().headline;
            assertThat(h).isNotBlank().doesNotContain("{").doesNotContain("NWS");
            assertThat(h.length()).as("%s headline length", product).isLessThanOrEqualTo(60);
        }
    }

    @Test
    void bodyAndHeadlineBothUnusable_stillSaysSomethingTrue() {
        assertThat(AlertDispatchService.sanitizeSlots("{a}.", ""))
                .isEqualTo(AlertDispatchService.LAST_RESORT_BODY);
        assertThat(AlertDispatchService.sanitizeSlots("{a}.", null))
                .isEqualTo(AlertDispatchService.LAST_RESORT_BODY);
        assertThat(AlertDispatchService.LAST_RESORT_BODY).doesNotContain("{");
    }

    @Test
    void aCleanBodyIsPassedThroughUnchanged() {
        String clean = "Flash flood happening now. Turn around, don't drown. Get to higher ground.";
        assertThat(AlertDispatchService.sanitizeSlots(clean, "x")).isEqualTo(clean);
    }

    @Test
    void doubledSpacesFromSubstitutionAreCollapsed() {
        assertThat(AlertDispatchService.sanitizeSlots("Too   many    spaces.", "x"))
                .isEqualTo("Too many spaces.");
    }

    @Test
    void aTemplateThatReintroducesASlotCannotReachAUser() {
        // The actual scenario the sanitizer exists for: someone edits
        // alert-dispatch-templates.json — which is hot-loaded, no deploy — and
        // adds a slot fillBody does not know.
        NormalizedAlert a = TestAlerts.nws("Tornado Warning").build();
        String body = AlertDispatchService.fillBody(
                "Tornado spotted {radius} miles away. Get to the lowest floor now.",
                a, "Tornado warning");

        assertThat(body).doesNotContain("{");
        assertThat(body).isEqualTo("Get to the lowest floor now.");
    }

    // ==================================================================
    // Substitution that should work, still works
    // ==================================================================

    @Test
    void magnitudeSubstitutes() {
        NormalizedAlert quake = TestAlerts.usgs("M5.6 — somewhere").area("somewhere").build();
        assertThat(AlertDispatchService.fillBody("A magnitude {mag} quake.", quake, "Earthquake"))
                .isEqualTo("A magnitude 5.6 quake.");
    }

    @Test
    void aNwsAlertWithNoMagnitudeDropsTheSentenceRatherThanPrintingTheSlot() {
        NormalizedAlert nws = TestAlerts.nws("Tornado Warning").build();
        assertThat(AlertDispatchService.fillBody("Magnitude {mag}. Take cover.", nws, "Tornado warning"))
                .isEqualTo("Take cover.");
    }

    @Test
    void aBlankPlaceDoesNotProduceADanglingSlot() {
        NormalizedAlert quake = TestAlerts.usgs("M5.6 — ").area("  ").build();
        assertThat(AlertDispatchService.fillBody("Quake at {place}. Check for damage.", quake, "Earthquake"))
                .isEqualTo("Check for damage.");
    }

    @Test
    void nullAndEmptyBodiesAreSafe() {
        NormalizedAlert a = TestAlerts.nws("Tornado Warning").build();
        assertThat(AlertDispatchService.fillBody(null, a, "x")).isEmpty();
        assertThat(AlertDispatchService.fillBody("", a, "x")).isEmpty();
        assertThat(AlertDispatchService.fillBody("Fine.", null, "x")).isEqualTo("Fine.");
        assertThat(AlertDispatchService.fillBody((DispatchTemplate) null, a)).isEmpty();
    }

    // ==================================================================
    // The {name} slot that resolved to the word "Warning"
    // ==================================================================

    @Test
    void hurricaneCopyNoLongerClaimsAStormNameItCannotKnow() {
        // inferAlertName took the headline's second token when the first was
        // "Hurricane". Real NWS headlines read "Hurricane Warning issued
        // August 30 at 5:00AM EDT by NWS Miami FL", so that token is
        // "Warning" — the copy rendered "Hurricane Warning is on the way."
        NormalizedAlert hurricane = TestAlerts.nws("Hurricane Warning")
                .headline("Hurricane Warning issued August 30 at 5:00AM EDT by NWS Miami FL")
                .build();

        String body = AlertDispatchService.fillBody(
                dispatcher.matchForAlert(hurricane).orElseThrow(), hurricane);

        assertThat(body).startsWith("Hurricane conditions are expected.");
        assertThat(body).doesNotContain("Hurricane Warning is on the way");
        assertThat(body).doesNotContain("{");
    }

    @Test
    void noTemplateStillDeclaresTheRemovedSlots() {
        for (String product : List.of("Hurricane Warning", "Typhoon Warning",
                "Tropical Storm Warning", "Storm Warning")) {
            DispatchTemplate t = dispatcher
                    .matchForAlert(TestAlerts.nws(product).build()).orElseThrow();
            assertThat(t.body)
                    .as("%s template", product)
                    .doesNotContain("{name}")
                    .doesNotContain("{distance}")
                    .doesNotContain("{direction}");
        }
    }
}
