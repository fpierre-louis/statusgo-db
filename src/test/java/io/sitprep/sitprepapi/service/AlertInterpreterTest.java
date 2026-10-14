package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.dto.AlertCardDto;
import io.sitprep.sitprepapi.dto.AlertFeedResponse;
import io.sitprep.sitprepapi.repo.AlertPostRepo;
import io.sitprep.sitprepapi.service.AlertIngestService.CapReference;
import io.sitprep.sitprepapi.service.AlertIngestService.MatchType;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.util.Vtec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S2 C2 — the card contract (§3) and {@link AlertInterpreter}'s rules 1–8.
 *
 * <p>Every test runs on a fixed serve clock. Alerts meant to be in force end in
 * 2099 and ended ones in 2020, so the safety policy's own clock agrees with the
 * pinned one whatever day the suite runs.</p>
 */
class AlertInterpreterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Instant NOW = Instant.parse("2026-10-13T18:00:00Z");
    private static final String FUTURE = "2099-01-01T00:00:00Z";
    private static final String PAST = "2020-01-01T00:00:00Z";

    /** San Carlos AZ — TestAlerts' default zone AZZ560 is one of these. */
    private static final Set<String> SAN_CARLOS = Set.of("AZZ560", "AZC007", "AZZ133");
    private static final double LAT = 33.3172, LNG = -110.5297;

    private AlertIngestService ingest;
    private AlertDispatchService dispatch;
    private AlertPostRepo alertPostRepo;
    private AlertFeedService feed;

    @BeforeEach
    void setUp() {
        NwsZoneService zones = new NwsZoneService();
        zones.seedPointZones(LAT, LNG, SAN_CARLOS);
        ingest = new AlertIngestService(zones);
        dispatch = spy(new AlertDispatchService(null, null, null, null, null, null, zones, null));
        dispatch.loadTemplates();
        alertPostRepo = mock(AlertPostRepo.class);
        when(alertPostRepo.findReadableThreadPosts(anyCollection(), anyString())).thenReturn(List.of());
        feed = new AlertFeedService(ingest, dispatch, alertPostRepo);
        feed.clock = Clock.fixed(NOW, ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static TestAlerts.Builder live(String event) {
        return TestAlerts.nws(event).id("urn:oid:" + event.replace(' ', '-')).endsAt(FUTURE);
    }

    private static TestAlerts.Builder watch(String event) {
        return TestAlerts.nwsWatch(event).id("urn:oid:" + event.replace(' ', '-')).endsAt(FUTURE);
    }

    /** A closed GeoJSON square of half-width {@code d} degrees around a point. */
    private static Map<String, Object> square(double lat, double lng, double d) {
        return Map.of("type", "Polygon", "coordinates", List.of(List.of(
                List.of(lng - d, lat - d), List.of(lng + d, lat - d),
                List.of(lng + d, lat + d), List.of(lng - d, lat + d),
                List.of(lng - d, lat - d))));
    }

    private static Vtec vtec(String action, String office, String ph, String sig, String etn) {
        return new Vtec(action, office, ph, sig, etn, null, null, 2026);
    }

    private AlertCardDto card(NormalizedAlert a) {
        return feed.toCard(a, MatchType.ZONE, 50);
    }

    private AlertCardDto cardIn(List<AlertCardDto> cards, String id) {
        return cards.stream().filter(c -> id.equals(c.id())).findFirst().orElseThrow(
                () -> new AssertionError("no card " + id + " in " + cards.stream().map(AlertCardDto::id).toList()));
    }

    private static List<String> actionTypes(AlertCardDto c) {
        return c.actions() == null ? List.of() : c.actions().stream().map(AlertCardDto.Action::type).toList();
    }

    private static AlertCardDto.Action action(AlertCardDto c, String type) {
        return c.actions().stream().filter(a -> type.equals(a.type())).findFirst().orElseThrow(
                () -> new AssertionError("no " + type + " in " + actionTypes(c)));
    }

    // ------------------------------------------------------------------
    // identity + version
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every card carries its dedupeKey, revision ref, version and source identity; interpretationRef stays null")
    void identityFields() {
        NormalizedAlert a = live("Flood Warning").senderName("NWS Salt Lake City UT").build();
        AlertCardDto c = card(a);

        assertThat(c.dedupeKey()).isEqualTo("NWS:urn:oid:Flood-Warning");
        assertThat(c.sourceRevisionRef()).startsWith("NWS:urn:oid:Flood-Warning@").hasSize("NWS:urn:oid:Flood-Warning@".length() + 12);
        assertThat(c.interpretationRef()).isNull();
        assertThat(c.presentationVersion()).isEqualTo(AlertInterpreter.VERSION);

        AlertCardDto.SourceIdentity id = c.sourceIdentity();
        assertThat(id.key()).isEqualTo("NWS");
        assertThat(id.name()).isEqualTo("National Weather Service");
        assertThat(id.official()).isTrue();
        assertThat(id.issuedBy()).isEqualTo("NWS Salt Lake City UT");
        assertThat(id.recordType()).isEqualTo("warning");
        assertThat(id.logoKey()).isNull();
    }

    // Every EXISTING card field keeping its pre-C2 value is pinned against a
    // golden capture from alerts/p3-C1 in AlertCardLegacyGoldenTest.

    // ------------------------------------------------------------------
    // the decision feed vs a person's feed
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("internal safety readers get the decision feed: no repository, no interpretation")
    class DecisionFeed {

        @Test
        @DisplayName("feedFor(lat, lng) never touches AlertPostRepo, however often it is called")
        void internalFeedMakesNoRepoCall() {
            ingest.setSnapshotForTest(List.of(live("Tornado Warning").build(), watch("Flood Watch").build()));
            for (int i = 0; i < 5; i++) {
                List<AlertCardDto> cards = feed.feedFor(LAT, LNG).alerts();
                assertThat(cards).hasSize(2);
                // Decision fields intact, interpretation absent.
                assertThat(cards).allSatisfy(c -> {
                    assertThat(c.lifecycleState()).isEqualTo("active");
                    assertThat(c.safety()).isNotNull();
                    assertThat(c.dedupeKey()).isNull();
                    assertThat(c.lifecycleInfo()).isNull();
                    assertThat(c.presentation()).isNull();
                    assertThat(c.viewerContext()).isNull();
                    assertThat(c.presentationVersion()).isNull();
                });
            }
            feed.feedFor(LAT, LNG, NOW);
            verify(alertPostRepo, never()).findReadableThreadPosts(anyCollection(), anyString());

            // ...while a person's feed asks exactly once.
            feed.feedForViewer(LAT, LNG, null, null);
            verify(alertPostRepo, times(1)).findReadableThreadPosts(anyCollection(), anyString());
        }

        @Test
        @DisplayName("ConcealmentSafetyService — once per push recipient — makes zero repository calls")
        void concealmentCheckMakesNoRepoCall() {
            ingest.setSnapshotForTest(List.of(live("Civil Danger Warning").instruction("Stay put.").build()));
            ConcealmentSafetyService concealment = new ConcealmentSafetyService(feed, dispatch);
            for (int i = 0; i < 3; i++) concealment.isConcealmentSensitiveAt(LAT, LNG);
            verify(alertPostRepo, never()).findReadableThreadPosts(anyCollection(), anyString());
        }

        @Test
        @DisplayName("the decision feed's legacy fields equal the person's feed's, card for card")
        void sameDecisionFieldsBothWays() {
            ingest.setSnapshotForTest(List.of(live("Tornado Warning").build(), watch("Flood Watch").build(),
                    live("Flood Warning").messageType("Update")
                            .vtec(List.of(vtec("EXP", "KPSR", "FL", "W", "0001"))).build()));
            List<AlertCardDto> internal = feed.feedFor(LAT, LNG).alerts();
            List<AlertCardDto> viewer = feed.feedForViewer(LAT, LNG, null, null).alerts();
            assertThat(internal).hasSameSizeAs(viewer);
            for (int i = 0; i < internal.size(); i++) {
                JsonNode a = MAPPER.valueToTree(internal.get(i));
                JsonNode b = MAPPER.valueToTree(viewer.get(i));
                for (String field : AlertCardLegacyGoldenTest.LEGACY_FIELDS) {
                    assertThat(a.get(field)).as(internal.get(i).id() + "." + field).isEqualTo(b.get(field));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Rule 1 — primary action
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("rule 1: primary action")
    class PrimaryAction {

        @Test
        @DisplayName("(a) current + guidance allowed → reviewed guidance, the card's own whatToDo")
        void reviewedGuidance() {
            AlertCardDto c = card(watch("Flood Watch").headline("FLOOD WATCH — EVACUATE NOW").build());
            assertThat(c.safety().guidanceMode()).isEqualTo("supplement_official");

            AlertCardDto.PrimaryAction pa = c.interpreter().primaryAction();
            assertThat(pa.kind()).isEqualTo("reviewed_guidance");
            assertThat(pa.text()).isEqualTo(c.whatToDo()).isNotBlank();
            assertThat(pa.attribution()).isEqualTo("SitPrep reviewed guidance");
            assertThat(pa.text()).doesNotContain("EVACUATE");
            assertThat(c.interpreter().summary()).isEqualTo(c.headline());
            assertThat(c.interpreter().steps()).isEqualTo(c.precautions());
            assertThat(c.interpreter().stepsSource()).isEqualTo(c.precautionsSource());
            assertThat(c.interpreter().author()).isEqualTo("sitprep");
        }

        @Test
        @DisplayName("(b) current + official-only → the issuer's instruction VERBATIM, attributed, no SitPrep steps")
        void officialInstructionVerbatim() {
            String raw = "  Shelter in place NOW.\nClose all windows and doors;  stay off the phone unless it is an emergency... "
                    + "x".repeat(600) + "\n";
            AlertCardDto c = card(live("Civil Danger Warning").instruction(raw).build());

            assertThat(c.safety().guidanceMode()).isEqualTo("official_only");
            AlertCardDto.PrimaryAction pa = c.interpreter().primaryAction();
            assertThat(pa.kind()).isEqualTo("official_instruction");
            assertThat(pa.text()).isEqualTo(raw);   // not trimmed, not shortened
            assertThat(pa.attribution()).isEqualTo("From the National Weather Service");
            assertThat(c.interpreter().summary()).isNull();
            assertThat(c.interpreter().steps()).isNull();
            assertThat(c.interpreter().author()).isEqualTo("issuer");
            assertThat(c.interpreter().evidence()).isNull();
        }

        @Test
        @DisplayName("(b) also for an alert no template covers, when the issuer sent an instruction")
        void officialInstructionWithoutTemplate() {
            AlertCardDto c = card(live("Special Weather Statement").instruction("Secure loose objects.").build());
            assertThat(c.safety().guidanceMode()).isEqualTo("no_guidance");
            assertThat(c.interpreter().primaryAction().kind()).isEqualTo("official_instruction");
            assertThat(c.interpreter().primaryAction().text()).isEqualTo("Secure loose objects.");
        }

        @Test
        @DisplayName("(c) no guidance and no instruction → null, never from title or severity")
        void nothingToSay() {
            AlertCardDto c = card(live("Special Weather Statement")
                    .severity("Extreme").headline("EVACUATE IMMEDIATELY — TAKE SHELTER").instruction("   ").build());
            assertThat(c.interpreter().primaryAction()).isNull();
            assertThat(c.interpreter().author()).isEqualTo("none");

            AlertCardDto blankOfficial = card(live("Civil Danger Warning").instruction(null).build());
            assertThat(blankOfficial.interpreter().primaryAction()).isNull();
        }

        @Test
        @DisplayName("a non-current card has no primary action even when an order was issued")
        void nonCurrentHasNone() {
            NormalizedAlert expiredOrder = live("Evacuation Immediate").endsAt(PAST)
                    .responseTypes(List.of("Evacuate")).instruction("Leave now via Route 9.").build();
            AlertCardDto c = card(expiredOrder);
            assertThat(c.lifecycleState()).isEqualTo("expired");
            assertThat(c.safety().asIssuedMovementDirective()).isEqualTo("evacuate");
            assertThat(c.interpreter().primaryAction()).isNull();

            AlertCardDto record = feed.toRecordCard(live("Flood Warning").build(), MatchType.ZONE, 50, "active");
            assertThat(record.interpreter().primaryAction()).isNull();
            assertThat(record.interpreter().summary()).isNull();
        }
    }

    // ------------------------------------------------------------------
    // Rule 2 — tone (R17)
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("rule 2: tone")
    class Tone {

        @Test
        @DisplayName("a watch is never danger, even when handed a critical_push decision")
        void watchNeverDangerEvenOnCriticalPush() {
            NormalizedAlert a = watch("Tornado Watch").severity("Extreme").build();
            AlertFeedService.Assessment real = feed.assess(a);
            AlertSafetyPolicy.Decision crit = new AlertSafetyPolicy.Decision(
                    AlertSafetyPolicy.DispatchMode.CRITICAL_PUSH, AlertSafetyPolicy.GuidanceMode.OFFICIAL_ONLY,
                    AlertSafetyPolicy.Compatibility.COMPATIBLE, Set.of(),
                    AlertSafetyPolicy.MovementDirective.NONE, "test");
            AlertCardDto c = feed.toCard(a, MatchType.ZONE, 50, null,
                    new AlertFeedService.Assessment(real.template(), crit));
            assertThat(c.presentation().tone()).isEqualTo("warning");
            assertThat(c.presentation().priority()).isEqualTo("life_safety");
        }

        @Test
        @DisplayName("an Update whose own VTEC is all CAN is a record: no primary action, no household doors")
        void ownCanUpdateIsARecord() {
            NormalizedAlert a = live("Tornado Warning").messageType("Update")
                    .response("Shelter").responseTypes(List.of("Shelter")).instruction("Take cover now.")
                    .vtec(List.of(vtec("CAN", "KPSR", "TO", "W", "0042"))).build();
            AlertCardDto c = card(a);
            assertThat(c.lifecycleInfo().state()).isEqualTo("cancelled");
            assertThat(c.interpreter().primaryAction()).isNull();
            assertThat(c.presentation().current()).isFalse();
            assertThat(c.presentation().tone()).isEqualTo("neutral");
            assertThat(actionTypes(c)).doesNotContain("OPEN_HOUSEHOLD_CHECKIN", "OPEN_EMERGENCY_PLAN", "START_POST");
        }

        @Test
        @DisplayName("danger only for a current warning-tier critical_push / attention alert")
        void danger() {
            assertThat(card(live("Tornado Warning").build()).presentation().tone()).isEqualTo("danger");
            assertThat(card(live("Flood Warning").build()).presentation().tone()).isEqualTo("danger");
        }

        @Test
        @DisplayName("a Severe or Extreme Watch is amber `warning`, never danger")
        void severeWatchIsWarning() {
            for (String sev : List.of("Severe", "Extreme", "Minor")) {
                AlertCardDto c = card(watch("Severe Thunderstorm Watch").severity(sev).build());
                assertThat(c.presentation().tone()).as(sev).isEqualTo("warning");
            }
        }

        @Test
        @DisplayName("attention for advisories, statements, information, and a warning whose mode is prepare/feed")
        void attention() {
            assertThat(card(live("Wind Advisory").build()).presentation().tone()).isEqualTo("attention");
            assertThat(card(live("Special Weather Statement").build()).presentation().tone()).isEqualTo("attention");
            AlertCardDto aq = card(live("Air Quality Alert").build());
            assertThat(aq.safety().dispatchMode()).isEqualTo("feed");
            assertThat(aq.presentation().tone()).isEqualTo("attention");
        }

        @Test
        @DisplayName("tone never comes from severity")
        void notFromSeverity() {
            String minor = card(live("Wind Advisory").severity("Minor").build()).presentation().tone();
            String extreme = card(live("Wind Advisory").severity("Extreme").build()).presentation().tone();
            assertThat(minor).isEqualTo(extreme);
        }

        @Test
        @DisplayName("FEMA and USGS records of fact are neutral")
        void femaAndUsgsNeutral() {
            NormalizedAlert quake = TestAlerts.usgs("M 6.1 - 10 km N of Somewhere").magnitude(6.1)
                    .geometry(Map.of("type", "Point", "coordinates", List.of(LNG, LAT, 10.0))).build();
            AlertCardDto q = feed.toCard(quake, MatchType.POLYGON, 50);
            assertThat(q.sourceIdentity().recordType()).isEqualTo("earthquake_information");
            assertThat(q.presentation().tone()).isEqualTo("neutral");

            AlertCardDto f = feed.toCard(TestAlerts.fema("Severe Storms (DR-4932-WV)").id("DR-4932-WV").build(),
                    MatchType.ZONE, 50);
            assertThat(f.sourceIdentity().recordType()).isEqualTo("disaster_declaration");
            assertThat(f.presentation().tone()).isEqualTo("neutral");
        }

        @Test
        @DisplayName("every record is neutral; all_clear only for a verified AllClear record")
        void records() {
            assertThat(card(live("Tornado Warning").endsAt(PAST).build()).presentation().tone()).isEqualTo("neutral");
            assertThat(feed.toRecordCard(live("Tornado Warning").build(), MatchType.ZONE, 50, "active")
                    .presentation().tone()).isEqualTo("neutral");

            NormalizedAlert allClear = live("Tornado Warning").responseTypes(List.of("AllClear")).urgency("Past").build();
            AlertCardDto ac = feed.toRecordCard(allClear, MatchType.ZONE, 50, "superseded");
            assertThat(ac.lifecycleInfo().verified()).isTrue();
            assertThat(ac.presentation().tone()).isEqualTo("all_clear");

            // Poll absence is never verified, so it can never read as all clear.
            AlertCardDto gone = feed.toRecordCard(allClear, MatchType.ZONE, 50, "no_longer_listed");
            assertThat(gone.presentation().tone()).isEqualTo("neutral");
        }
    }

    // ------------------------------------------------------------------
    // Rule 3 — priority
    // ------------------------------------------------------------------

    @Test
    @DisplayName("rule 3: priority — life_safety, urgent, prepare, info, record")
    void priority() {
        assertThat(card(live("Tornado Warning").build()).presentation().priority()).isEqualTo("life_safety");
        // attention mode but a movement directive (avoid_area) → life safety
        AlertCardDto flood = card(live("Flood Warning").build());
        assertThat(flood.safety().movementDirective()).isEqualTo("avoid_area");
        assertThat(flood.presentation().priority()).isEqualTo("life_safety");
        // warning tier, attention, no directive
        AlertCardDto statement = card(live("Flood Statement").responseTypes(List.of("Monitor")).build());
        assertThat(statement.safety().movementDirective()).isEqualTo("none");
        assertThat(statement.presentation().priority()).isEqualTo("urgent");
        assertThat(card(watch("Flood Watch").build()).presentation().priority()).isEqualTo("prepare");
        assertThat(card(live("Wind Advisory").responseTypes(List.of("Monitor")).build()).presentation().priority())
                .isEqualTo("info");
        assertThat(card(live("Tornado Warning").endsAt(PAST).build()).presentation().priority()).isEqualTo("record");
        assertThat(feed.toRecordCard(live("Tornado Warning").build(), MatchType.ZONE, 50, "active")
                .presentation().priority()).isEqualTo("record");
    }

    @Test
    @DisplayName("interruptiveEligible = current ∧ critical_push")
    void interruptive() {
        assertThat(card(live("Tornado Warning").build()).presentation().interruptiveEligible()).isTrue();
        assertThat(card(live("Flood Warning").build()).presentation().interruptiveEligible()).isFalse();
        assertThat(card(live("Tornado Warning").endsAt(PAST).build()).presentation().interruptiveEligible()).isFalse();
        assertThat(card(live("Tornado Warning").build()).presentation().current()).isTrue();
        assertThat(feed.toRecordCard(live("Tornado Warning").build(), MatchType.ZONE, 50, "active")
                .presentation().current()).isFalse();
    }

    // ------------------------------------------------------------------
    // Rule 4 — actions
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("rule 4: actions")
    class Actions {

        @Test
        @DisplayName("OPEN_ALERT_DETAIL is always present, with identity params and a route")
        void detailAlways() {
            AlertCardDto c = card(watch("Flood Watch").build());
            AlertCardDto.Action d = action(c, "OPEN_ALERT_DETAIL");
            assertThat(d.params()).containsEntry("dedupeKey", c.dedupeKey())
                    .containsEntry("revisionRef", c.sourceRevisionRef())
                    .containsEntry("context", "current");
            assertThat(d.fallbackRoute()).isEqualTo("/hazards?alert=NWS%3Aurn%3Aoid%3AFlood-Watch");

            AlertCardDto record = feed.toRecordCard(watch("Flood Watch").build(), MatchType.ZONE, 50, "expired");
            assertThat(actionTypes(record)).contains("OPEN_ALERT_DETAIL");
        }

        @Test
        @DisplayName("OPEN_ALERT_MAP only with a bbox or zones")
        void mapNeedsAnArea() {
            ingest.setSnapshotForTest(List.of(watch("Flood Watch").build()));
            AlertCardDto zoned = feed.feedForViewer(LAT, LNG, null, null).alerts().get(0);
            assertThat(actionTypes(zoned)).contains("OPEN_ALERT_MAP");

            AlertCardDto polygon = feed.toCard(live("Tornado Warning").ugc(List.of()).same(List.of())
                    .geometry(square(LAT, LNG, 0.1)).build(), MatchType.POLYGON, 50);
            AlertCardDto.Action map = action(polygon, "OPEN_ALERT_MAP");
            assertThat(map.params()).containsKey("bbox");
            assertThat(map.fallbackRoute()).startsWith("/map?").contains("select=alert%3Aurn%3Aoid%3ATornado-Warning")
                    .contains("bbox=");

            AlertCardDto nowhere = feed.toCard(TestAlerts.fema("Severe Storms").id("DR-1-XX").build(), MatchType.ZONE, 50);
            assertThat(actionTypes(nowhere)).doesNotContain("OPEN_ALERT_MAP");
        }

        @Test
        @DisplayName("every OPEN_ALERT_MAP route carries a camera point; a zone-only card with no viewer has no map action")
        void mapRouteAlwaysHasACamera() {
            // History builds records with no viewer. A zone-only record has no
            // box to centre on, and the map drops a bare `select=` link, so the
            // action must not be emitted at all (accepted deviation 2026-10-14).
            AlertCardDto history = feed.toRecordCard(watch("Flood Watch").build(), MatchType.ZONE, 50, "expired");
            assertThat(actionTypes(history)).doesNotContain("OPEN_ALERT_MAP");
            assertThat(actionTypes(card(watch("Flood Watch").build()))).doesNotContain("OPEN_ALERT_MAP");

            // With a viewer, the zone-only card centres on the viewer.
            ingest.setSnapshotForTest(List.of(watch("Flood Watch").build()));
            AlertCardDto viewed = feed.feedForViewer(LAT, LNG, null, null).alerts().get(0);
            assertThat(action(viewed, "OPEN_ALERT_MAP").fallbackRoute())
                    .startsWith("/map?lat=" + LAT + "&lng=" + LNG + "&select=");

            // A polygon record with no viewer centres on its box.
            AlertCardDto polyRecord = feed.toRecordCard(live("Tornado Warning").ugc(List.of()).same(List.of())
                    .geometry(square(LAT, LNG, 0.1)).build(), MatchType.POLYGON, 50, "expired");
            assertThat(action(polyRecord, "OPEN_ALERT_MAP").fallbackRoute()).contains("lat=").contains("lng=");
        }

        @Test
        @DisplayName("a polygon across the antimeridian gets a wrapped box (west > east), centred near 180, not at lng 0")
        void antimeridianPolygon() {
            Map<String, Object> aleutians = Map.of("type", "Polygon", "coordinates", List.of(List.of(
                    List.of(179.0, 51.0), List.of(-179.5, 51.0), List.of(-179.5, 52.0),
                    List.of(179.0, 52.0), List.of(179.0, 51.0))));
            AlertCardDto c = feed.toCard(live("High Wind Warning").ugc(List.of("AKZ185")).same(List.of())
                    .geometry(aleutians).build(), MatchType.POLYGON, 50);
            assertThat(c.officialArea().bbox()).containsExactly(51.0, 179.0, 52.0, -179.5);

            AlertCardDto.Action map = action(c, "OPEN_ALERT_MAP");
            // The map's bbox reader refuses west > east, so the wrapped box is not
            // sent to it; the camera centres on the true middle of the area.
            assertThat(map.params()).doesNotContainKey("bbox");
            assertThat(map.fallbackRoute()).startsWith("/map?lat=51.5&lng=179.75&select=")
                    .doesNotContain("bbox=");

            // A MultiPolygon split at the antimeridian reads the same way.
            Map<String, Object> split = Map.of("type", "MultiPolygon", "coordinates", List.of(
                    List.of(List.of(List.of(179.0, 51.0), List.of(180.0, 51.0), List.of(180.0, 52.0),
                            List.of(179.0, 52.0), List.of(179.0, 51.0))),
                    List.of(List.of(List.of(-180.0, 51.0), List.of(-179.5, 51.0), List.of(-179.5, 52.0),
                            List.of(-180.0, 52.0), List.of(-180.0, 51.0)))));
            assertThat(AlertInterpreter.bboxOf(split)).containsExactly(51.0, 179.0, 52.0, -179.5);

            // An ordinary polygon is untouched.
            assertThat(AlertInterpreter.bboxOf(square(LAT, LNG, 0.1)))
                    .containsExactly(LAT - 0.1, LNG - 0.1, LAT + 0.1, LNG + 0.1);
        }

        @Test
        @DisplayName("OPEN_GUIDE when a hazard guide exists; commerce suppressed while current")
        void guide() {
            AlertCardDto current = card(watch("Flood Watch").build());
            AlertCardDto.Action g = action(current, "OPEN_GUIDE");
            assertThat(g.params()).containsEntry("hazardKey", "flood").containsEntry("commerceSuppressed", true);
            assertThat(g.fallbackRoute()).isEqualTo("/flood-prep");

            AlertCardDto record = feed.toRecordCard(watch("Flood Watch").build(), MatchType.ZONE, 50, "expired");
            assertThat(action(record, "OPEN_GUIDE").params()).containsEntry("commerceSuppressed", false);

            // hazardType "other" has no guide.
            assertThat(actionTypes(card(watch("Severe Thunderstorm Watch").build()))).doesNotContain("OPEN_GUIDE");
        }

        @Test
        @DisplayName("check-in and plan only on a current life_safety card; never an activation; START_POST never on a record")
        void householdActionsOnlyForCurrentLifeSafety() {
            AlertCardDto tornado = card(live("Tornado Warning").build());
            assertThat(actionTypes(tornado)).contains("OPEN_HOUSEHOLD_CHECKIN", "OPEN_EMERGENCY_PLAN");
            assertThat(actionTypes(tornado)).noneMatch(t -> t.contains("ACTIVAT") || t.contains("START_CHECK"));

            assertThat(actionTypes(card(watch("Flood Watch").build())))
                    .doesNotContain("OPEN_HOUSEHOLD_CHECKIN", "OPEN_EMERGENCY_PLAN");
            AlertCardDto ended = card(live("Tornado Warning").endsAt(PAST).build());
            assertThat(actionTypes(ended)).doesNotContain("OPEN_HOUSEHOLD_CHECKIN", "OPEN_EMERGENCY_PLAN", "START_POST");
            AlertCardDto history = feed.toRecordCard(live("Tornado Warning").build(), MatchType.ZONE, 50, "active");
            assertThat(actionTypes(history)).doesNotContain("OPEN_HOUSEHOLD_CHECKIN", "OPEN_EMERGENCY_PLAN", "START_POST");
        }
    }

    // ------------------------------------------------------------------
    // Rule 5 — viewer context + official area
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("rule 5: viewerContext")
    class Viewer {

        private AlertCardDto only(AlertFeedResponse r) {
            assertThat(r.alerts()).hasSize(1);
            return r.alerts().get(0);
        }

        @Test
        @DisplayName("a polygon containing the viewer → inside")
        void inside() {
            ingest.setSnapshotForTest(List.of(live("Tornado Warning").ugc(List.of()).same(List.of())
                    .geometry(square(LAT, LNG, 0.1)).build()));
            AlertCardDto c = only(feed.feedForViewer(LAT, LNG, null, null));
            assertThat(c.viewerContext().matchType()).isEqualTo("polygon");
            assertThat(c.viewerContext().containment()).isEqualTo("inside");
            assertThat(c.viewerContext().role()).isEqualTo("CURRENT_LOCATION");

            AlertCardDto.OfficialArea area = c.officialArea();
            assertThat(area.role()).isEqualTo("OFFICIAL_ALERT_AREA");
            assertThat(area.geometryKind()).isEqualTo("polygon");
            assertThat(area.bbox()).containsExactly(LAT - 0.1, LNG - 0.1, LAT + 0.1, LNG + 0.1);
            assertThat(area.confidence()).isEqualTo("exact");
            assertThat(area.provenance()).isEqualTo("NWS");
        }

        @Test
        @DisplayName("a nearby polygon that does not contain the viewer → outside, while matchType still says polygon")
        void outside() {
            ingest.setSnapshotForTest(List.of(live("Tornado Warning").ugc(List.of()).same(List.of())
                    .geometry(square(LAT + 0.3, LNG + 0.3, 0.05)).build()));
            AlertCardDto c = only(feed.feedForViewer(LAT, LNG, null, null));
            assertThat(c.viewerContext().matchType()).isEqualTo("polygon");
            assertThat(c.viewerContext().containment()).isEqualTo("outside");
        }

        @Test
        @DisplayName("an unreadable polygon → unknown, never outside")
        void unknown() {
            Map<String, Object> broken = Map.of("type", "Polygon", "coordinates",
                    List.of(List.of(List.of(LNG, LAT), List.of(LNG + 0.1, LAT))));
            AlertCardDto c = feed.toCard(live("Tornado Warning").ugc(List.of()).geometry(broken).build(),
                    MatchType.POLYGON, 50);
            // toCard carries no viewer point; the feed path does.
            ingest.setSnapshotForTest(List.of(live("Tornado Warning").ugc(List.of()).same(List.of())
                    .geometry(broken).build()));
            AlertCardDto viaFeed = only(feed.feedForViewer(LAT, LNG, null, null));
            assertThat(viaFeed.viewerContext().containment()).isEqualTo("unknown");
            assertThat(c.viewerContext()).isNull();
        }

        @Test
        @DisplayName("a zone match → zone; region area with the zones and no bbox")
        void zone() {
            ingest.setSnapshotForTest(List.of(watch("Flood Watch").build()));
            AlertCardDto c = only(feed.feedForViewer(LAT, LNG, null, null));
            assertThat(c.viewerContext().matchType()).isEqualTo("zone");
            assertThat(c.viewerContext().containment()).isEqualTo("zone");
            assertThat(c.officialArea().geometryKind()).isEqualTo("zone");
            assertThat(c.officialArea().zones()).containsExactly("AZZ560");
            assertThat(c.officialArea().bbox()).isNull();
            assertThat(c.officialArea().confidence()).isEqualTo("region");
        }

        @Test
        @DisplayName("context=home → HOUSEHOLD_LOCATION and meta.context=home; default and junk → current")
        void context() {
            ingest.setSnapshotForTest(List.of(watch("Flood Watch").build()));
            AlertFeedResponse home = feed.feedForViewer(LAT, LNG, null, "home");
            assertThat(home.meta().context()).isEqualTo("home");
            AlertCardDto c = only(home);
            assertThat(c.viewerContext().role()).isEqualTo("HOUSEHOLD_LOCATION");
            assertThat(action(c, "OPEN_ALERT_DETAIL").params()).containsEntry("context", "home");
            assertThat(action(c, "OPEN_ALERT_DETAIL").fallbackRoute()).startsWith("/hazards?scope=home&alert=");

            assertThat(feed.feedForViewer(LAT, LNG, null, null).meta().context()).isEqualTo("current");
            assertThat(feed.feedForViewer(LAT, LNG, null, "HOME").meta().context()).isEqualTo("home");
            assertThat(feed.feedForViewer(LAT, LNG, null, "elsewhere").meta().context()).isEqualTo("current");
        }

        @Test
        @DisplayName("coordinate age travels on the viewer block")
        void coordinateAge() {
            ingest.setSnapshotForTest(List.of(watch("Flood Watch").build()));
            Instant old = NOW.minus(java.time.Duration.ofDays(60));
            AlertCardDto c = only(feed.feedForViewer(LAT, LNG, old, null));
            assertThat(c.viewerContext().coordinateCapturedAt()).isEqualTo(old.toString());
            assertThat(c.viewerContext().coordinateStale()).isTrue();

            AlertCardDto unstamped = only(feed.feedForViewer(LAT, LNG, null, null));
            assertThat(unstamped.viewerContext().coordinateCapturedAt()).isNull();
            assertThat(unstamped.viewerContext().coordinateStale()).isNull();
        }
    }

    // ------------------------------------------------------------------
    // Rule 6 — decision once
    // ------------------------------------------------------------------

    @Test
    @DisplayName("rule 6: the feed decides once per alert; the interpreter never re-evaluates")
    void decisionOnce() {
        List<NormalizedAlert> snapshot = List.of(
                live("Tornado Warning").build(),
                watch("Flood Watch").build(),
                live("Civil Danger Warning").instruction("Stay put.").build());
        ingest.setSnapshotForTest(snapshot);
        AlertFeedService counted = spy(feed);
        assertThat(counted.feedForViewer(LAT, LNG, null, "home").alerts()).hasSize(3);
        verify(dispatch, times(3)).matchForAlert(any());
        verify(counted, times(3)).assess(any());
    }

    @Test
    @DisplayName("rule 6: the interpreter follows the Assessment it is handed, not a fresh policy call")
    void interpreterTakesTheAssessment() {
        // CAP Shelter: compatible with the approved tornado template, so the
        // policy itself would allow SitPrep guidance here.
        NormalizedAlert tornado = live("Tornado Warning").response("Shelter").instruction("Go to the basement.").build();
        AlertFeedService.Assessment real = feed.assess(tornado);
        assertThat(real.decision().allowsSitPrepGuidance()).isTrue();
        org.mockito.Mockito.clearInvocations(dispatch);   // the lookup above is the test's own
        // The same template with a decision that says official-only: the card
        // must follow THIS decision, which a re-evaluation would contradict.
        AlertSafetyPolicy.Decision officialOnly = new AlertSafetyPolicy.Decision(
                AlertSafetyPolicy.DispatchMode.CRITICAL_PUSH, AlertSafetyPolicy.GuidanceMode.OFFICIAL_ONLY,
                AlertSafetyPolicy.Compatibility.COMPATIBLE, Set.of(), AlertSafetyPolicy.MovementDirective.NONE, "test");
        AlertCardDto c = feed.toCard(tornado, MatchType.ZONE, 50, null,
                new AlertFeedService.Assessment(real.template(), officialOnly));
        assertThat(c.interpreter().primaryAction().kind()).isEqualTo("official_instruction");
        assertThat(c.interpreter().primaryAction().text()).isEqualTo("Go to the basement.");
        assertThat(c.interpreter().guidanceMode()).isEqualTo("official_only");
        verify(dispatch, never()).matchForAlert(tornado);
    }

    // ------------------------------------------------------------------
    // Rule 8 — lifecycle verified / reason
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("rule 8: lifecycleInfo")
    class Lifecycle {

        private final Vtec newWatch = vtec("NEW", "KSLC", "FF", "A", "0007");

        private NormalizedAlert original() {
            return watch("Flash Flood Watch").id("urn:oid:A").messageType("Alert")
                    .sent("2026-10-13T12:00:00Z").vtec(List.of(newWatch)).build();
        }

        private TestAlerts.Builder replacing(String action) {
            return watch("Flash Flood Watch").id("urn:oid:B").messageType("Update")
                    .sent("2026-10-13T17:00:00Z")
                    .referenceTuples(List.of(new CapReference("w-nws.webmaster@noaa.gov", "urn:oid:A", "2026-10-13T12:00:00Z")))
                    .vtec(List.of(vtec(action, "KSLC", "FF", "A", "0007")));
        }

        private AlertCardDto replacedCard(NormalizedAlert b) {
            ingest.setSnapshotForTest(List.of(original(), b));
            return cardIn(feed.feedForViewer(LAT, LNG, null, null).alerts(), "urn:oid:A");
        }

        @Test
        @DisplayName("an own VTEC CON is `updated` even on a CAP Alert; lifecycleState keeps the shared answer")
        void ownConOnAnAlert() {
            AlertCardDto c = card(replacing("CON").messageType("Alert").build());
            assertThat(c.lifecycleInfo().state()).isEqualTo("updated");
            assertThat(c.lifecycleInfo().reason()).isEqualTo("vtec_con");
            assertThat(c.lifecycleInfo().verified()).isTrue();
            assertThat(c.lifecycleState()).isEqualTo("active");   // AlertDerivations, unchanged
            assertThat(c.presentation().current()).isTrue();
        }

        @Test
        @DisplayName("an own all-terminal VTEC on a live Update: lifecycleInfo AND the decision fields end it (P1)")
        void ownTerminalVtec() {
            for (String act : List.of("EXP", "CAN")) {
                NormalizedAlert a = live("Flood Warning").messageType("Update")
                        .sent("2026-10-13T17:00:00Z")
                        .vtec(List.of(vtec(act, "KPSR", "FL", "W", "0001"))).build();
                AlertCardDto c = card(a);
                // P1 (handoff §27): the shared derivation — what the dispatcher,
                // ConcealmentSafetyService and ActivationDirectiveResolver act on —
                // reads the own VTEC too. Before P1 this card said `updated` with
                // SitPrep steps and a critical_push dispatch mode.
                String ended = act.equals("EXP") ? "expired" : "cancelled";
                assertThat(c.lifecycleState()).as(act).isEqualTo(ended);
                assertThat(c.whatToDo()).as(act).isNull();
                assertThat(c.safety().dispatchMode()).as(act).isEqualTo("suppress");
                assertThat(c.safety().reason()).as(act).isEqualTo("vtec_terminal");
                assertThat(c.safety().movementDirective()).as(act).isEqualTo("none");
                assertThat(c.lifecycleInfo().state()).as(act).isEqualTo(ended).isEqualTo(c.lifecycleState());
                assertThat(c.lifecycleInfo().verified()).isTrue();
                assertThat(c.lifecycleInfo().reason()).isEqualTo("vtec_" + act.toLowerCase());
                // It ended when NWS said so, not at the future end time it still carries.
                assertThat(c.lifecycleInfo().endedAt()).as(act).isEqualTo("2026-10-13T17:00:00Z");
                assertThat(c.presentation().current()).isFalse();
                assertThat(c.presentation().priority()).isEqualTo("record");
                assertThat(c.presentation().tone()).isEqualTo("neutral");
                assertThat(c.presentation().interruptiveEligible()).isFalse();
                assertThat(c.interpreter().primaryAction()).isNull();
                assertThat(c.interpreter().summary()).isNull();
                assertThat(c.interpreter().steps()).isNull();
                assertThat(c.interpreter().author()).isEqualTo("none");
                assertThat(actionTypes(c)).doesNotContain("OPEN_HOUSEHOLD_CHECKIN", "OPEN_EMERGENCY_PLAN");
            }
        }

        @Test
        @DisplayName("an own VTEC CON / EXT is `updated`, verified, on the VTEC thread")
        void ownCon() {
            for (String act : List.of("CON", "EXT", "EXA", "EXB")) {
                AlertCardDto c = card(replacing(act).build());
                AlertCardDto.LifecycleInfo li = c.lifecycleInfo();
                assertThat(li.state()).as(act).isEqualTo("updated").isEqualTo(c.lifecycleState());
                assertThat(li.current()).isTrue();
                assertThat(li.verified()).isTrue();
                assertThat(li.reason()).isEqualTo("vtec_" + act.toLowerCase());
                assertThat(li.threadKey()).isEqualTo("VTEC:KSLC.FF.A.0007.2026");
                assertThat(li.references()).singleElement().satisfies(r -> {
                    assertThat(r.identifier()).isEqualTo("urn:oid:A");
                    assertThat(r.sender()).isEqualTo("w-nws.webmaster@noaa.gov");
                    assertThat(r.sent()).isEqualTo("2026-10-13T12:00:00Z");
                    assertThat(r.resolved()).isFalse();
                });
                assertThat(li.updatedAt()).isEqualTo("2026-10-13T17:00:00Z");
            }
        }

        @Test
        @DisplayName("replaced by a VTEC CAN → cancelled; by EXP → expired; by UPG → superseded; by CON → superseded/vtec_con")
        void replacedByVtec() {
            AlertCardDto can = replacedCard(replacing("CAN").messageType("Cancel").response("AllClear").build());
            assertThat(can.lifecycleState()).isEqualTo("cancelled");
            assertThat(can.lifecycleInfo().reason()).isEqualTo("vtec_can");
            assertThat(can.lifecycleInfo().verified()).isTrue();
            assertThat(can.lifecycleInfo().current()).isFalse();
            assertThat(can.lifecycleInfo().replacedBy()).isEqualTo("NWS:urn:oid:B");
            assertThat(can.lifecycleInfo().endedAt()).isEqualTo("2026-10-13T17:00:00Z");

            AlertCardDto exp = replacedCard(replacing("EXP").messageType("Cancel").response("AllClear").build());
            // lifecycleState stays the shared CAP answer; VTEC's more specific
            // "expired" is lifecycleInfo's.
            assertThat(exp.lifecycleState()).isEqualTo("cancelled");
            assertThat(exp.lifecycleInfo().state()).isEqualTo("expired");
            assertThat(exp.lifecycleInfo().reason()).isEqualTo("vtec_exp");
            assertThat(exp.lifecycleInfo().verified()).isTrue();

            AlertCardDto upg = replacedCard(watch("Flash Flood Warning").id("urn:oid:B").messageType("Alert")
                    .referenceTuples(List.of(new CapReference("s", "urn:oid:A", "2026-10-13T12:00:00Z")))
                    .vtec(List.of(vtec("UPG", "KSLC", "FF", "A", "0007"), vtec("NEW", "KSLC", "FF", "W", "0042")))
                    .build());
            assertThat(upg.lifecycleState()).isEqualTo("superseded");
            assertThat(upg.lifecycleInfo().reason()).isEqualTo("vtec_upg");
            assertThat(upg.lifecycleInfo().verified()).isTrue();

            AlertCardDto con = replacedCard(replacing("CON").build());
            assertThat(con.lifecycleState()).isEqualTo("superseded");
            assertThat(con.lifecycleInfo().reason()).isEqualTo("vtec_con");
            // ...and the replacing message sits on the same thread
            AlertCardDto b = cardIn(feed.feedForViewer(LAT, LNG, null, null).alerts(), "urn:oid:B");
            assertThat(b.lifecycleInfo().threadKey()).isEqualTo(con.lifecycleInfo().threadKey());
            assertThat(b.lifecycleInfo().references().get(0).resolved()).isTrue();
        }

        @Test
        @DisplayName("the upgrading Warning is active on its OWN thread")
        void upgradeTargetIsActive() {
            NormalizedAlert warning = watch("Flash Flood Warning").id("urn:oid:W").messageType("Alert")
                    .vtec(List.of(vtec("UPG", "KSLC", "FF", "A", "0007"), vtec("NEW", "KSLC", "FF", "W", "0042")))
                    .build();
            AlertCardDto c = card(warning);
            assertThat(c.lifecycleInfo().state()).isEqualTo("active");
            assertThat(c.lifecycleInfo().reason()).isEqualTo("vtec_new");
            assertThat(c.lifecycleInfo().threadKey()).isEqualTo("VTEC:KSLC.FF.W.0042.2026");
        }

        @Test
        @DisplayName("CAP-only edges: Update → superseded/cap_update, Cancel → cancelled/cap_cancel")
        void capOnly() {
            NormalizedAlert a = watch("Flood Watch").id("urn:oid:A").build();
            NormalizedAlert update = watch("Flood Watch").id("urn:oid:B").messageType("Update")
                    .references(List.of("urn:oid:A")).build();
            ingest.setSnapshotForTest(List.of(a, update));
            AlertCardDto superseded = cardIn(feed.feedForViewer(LAT, LNG, null, null).alerts(), "urn:oid:A");
            assertThat(superseded.lifecycleState()).isEqualTo("superseded");
            assertThat(superseded.lifecycleInfo().reason()).isEqualTo("cap_update");
            assertThat(superseded.lifecycleInfo().verified()).isTrue();
            assertThat(superseded.lifecycleInfo().threadKey()).isEqualTo("CAP:urn:oid:A");

            AlertCardDto updated = cardIn(feed.feedForViewer(LAT, LNG, null, null).alerts(), "urn:oid:B");
            assertThat(updated.lifecycleInfo().reason()).isEqualTo("cap_update");
            assertThat(updated.lifecycleInfo().threadKey()).isEqualTo("CAP:urn:oid:A");

            NormalizedAlert cancel = watch("Flood Watch").id("urn:oid:C").messageType("Cancel")
                    .references(List.of("urn:oid:A")).build();
            ingest.setSnapshotForTest(List.of(a, cancel));
            AlertCardDto cancelled = cardIn(feed.feedForViewer(LAT, LNG, null, null).alerts(), "urn:oid:A");
            assertThat(cancelled.lifecycleState()).isEqualTo("cancelled");
            assertThat(cancelled.lifecycleInfo().reason()).isEqualTo("cap_cancel");
        }

        @Test
        @DisplayName("own times: expired is verified, with the end time")
        void expiredByOwnTimes() {
            AlertCardDto c = card(live("Tornado Warning").endsAt(PAST).build());
            assertThat(c.lifecycleInfo().state()).isEqualTo("expired");
            assertThat(c.lifecycleInfo().reason()).isEqualTo("expired");
            assertThat(c.lifecycleInfo().verified()).isTrue();
            assertThat(c.lifecycleInfo().endedAt()).isEqualTo(PAST);
        }

        @Test
        @DisplayName("poll absence → no_longer_listed, NOT verified")
        void pollAbsence() {
            AlertCardDto c = feed.toRecordCard(watch("Flood Watch").build(), MatchType.ZONE, 50, "no_longer_listed");
            assertThat(c.lifecycleInfo().state()).isEqualTo("no_longer_listed");
            assertThat(c.lifecycleInfo().verified()).isFalse();
            assertThat(c.lifecycleInfo().reason()).isEqualTo("not_listed");
            assertThat(c.lifecycleInfo().current()).isFalse();
        }

        @Test
        @DisplayName("a history record ended by a stored reverse edge is verified")
        void historyReverseEdge() {
            AlertCardDto superseded = feed.toRecordCard(watch("Flood Watch").build(), MatchType.ZONE, 50, "superseded");
            assertThat(superseded.lifecycleInfo().verified()).isTrue();
            assertThat(superseded.lifecycleInfo().reason()).isEqualTo("replaced");
            // Cancelled with no ending message in hand: verified, but how is unknown.
            AlertCardDto cancelled = feed.toRecordCard(watch("Flood Watch").build(), MatchType.ZONE, 50, "cancelled");
            assertThat(cancelled.lifecycleInfo().verified()).isTrue();
            assertThat(cancelled.lifecycleInfo().reason()).isNull();
        }

        @Test
        @DisplayName("a cancelled record says HOW when the ending message is in hand: all_clear vs cap_cancel")
        void cancelledReasonFromEndingMessage() {
            NormalizedAlert a = watch("Flood Watch").id("urn:oid:A").build();
            NormalizedAlert allClear = watch("Flood Watch").id("urn:oid:B").messageType("Update")
                    .response("AllClear").references(List.of("urn:oid:A")).build();
            AlertCardDto byAllClear = feed.toRecordCard(a, MatchType.ZONE, 50, "cancelled", null, allClear);
            assertThat(byAllClear.lifecycleState()).isEqualTo("cancelled");
            assertThat(byAllClear.lifecycleInfo().reason()).isEqualTo("all_clear");
            assertThat(byAllClear.lifecycleInfo().replacedBy()).isEqualTo("NWS:urn:oid:B");
            assertThat(byAllClear.presentation().tone()).isEqualTo("all_clear");

            NormalizedAlert cancel = watch("Flood Watch").id("urn:oid:C").messageType("Cancel")
                    .references(List.of("urn:oid:A")).build();
            AlertCardDto byCancel = feed.toRecordCard(a, MatchType.ZONE, 50, "cancelled", null, cancel);
            assertThat(byCancel.lifecycleInfo().reason()).isEqualTo("cap_cancel");
            assertThat(byCancel.presentation().tone()).isEqualTo("neutral");
        }

        @Test
        @DisplayName("poll absence never outranks the alert's own verified VTEC ending")
        void ownVtecOutranksPollAbsence() {
            NormalizedAlert a = live("Tornado Warning").messageType("Update")
                    .vtec(List.of(vtec("CAN", "KPSR", "TO", "W", "0042"))).build();
            AlertCardDto c = feed.toRecordCard(a, MatchType.ZONE, 50, "no_longer_listed");
            assertThat(c.lifecycleState()).isEqualTo("no_longer_listed");   // history's answer, unchanged
            assertThat(c.lifecycleInfo().state()).isEqualTo("cancelled");
            assertThat(c.lifecycleInfo().verified()).isTrue();
            assertThat(c.lifecycleInfo().reason()).isEqualTo("vtec_can");
        }

        @Test
        @DisplayName("USGS and FEMA thread on their dedupeKey")
        void nonCapThread() {
            AlertCardDto q = feed.toCard(TestAlerts.usgs("M 5.6").id("us7000abc").revisionUpdatedAt("2026-10-13T17:30:00Z").build(),
                    MatchType.POLYGON, 50);
            assertThat(q.lifecycleInfo().threadKey()).isEqualTo("USGS:us7000abc");
            assertThat(q.lifecycleInfo().updatedAt()).isEqualTo("2026-10-13T17:30:00Z");
        }
    }

    // ------------------------------------------------------------------
    // community threads — one batched lookup
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("communityThreads")
    class Community {

        private AlertPostRepo.ThreadPostRef ref(String alertId, long postId) {
            return new AlertPostRepo.ThreadPostRef() {
                @Override public String getAlertId() { return alertId; }
                @Override public Long getPostId() { return postId; }
            };
        }

        @Test
        @DisplayName("N cards cost ONE repository call, keyed the dispatcher's way across the revision chain")
        @SuppressWarnings("unchecked")
        void oneQueryForTheFeed() {
            NormalizedAlert a = watch("Flood Watch").id("urn:oid:A").build();
            NormalizedAlert b = live("Tornado Warning").id("urn:oid:T").references(List.of("urn:oid:T0")).build();
            NormalizedAlert c = live("Wind Advisory").id("urn:oid:W").build();
            ingest.setSnapshotForTest(List.of(a, b, c));
            when(alertPostRepo.findReadableThreadPosts(anyCollection(), anyString())).thenReturn(List.of(
                    ref("NWS-urn:oid:A", 12L), ref("NWS-urn:oid:A", 11L), ref("NWS-urn:oid:T0", 30L)));

            List<AlertCardDto> cards = feed.feedForViewer(LAT, LNG, null, null).alerts();
            assertThat(cards).hasSize(3);

            org.mockito.ArgumentCaptor<Collection<String>> keys = org.mockito.ArgumentCaptor.forClass(Collection.class);
            verify(alertPostRepo, times(1)).findReadableThreadPosts(keys.capture(),
                    org.mockito.ArgumentMatchers.eq(io.sitprep.sitprepapi.constant.SystemAccounts.SITPREP_EMAIL));
            assertThat(keys.getValue()).containsExactlyInAnyOrder(
                    "NWS-urn:oid:A", "NWS-urn:oid:T", "NWS-urn:oid:T0", "NWS-urn:oid:W");

            AlertCardDto ca = cardIn(cards, "urn:oid:A");
            assertThat(ca.communityThreads().postIds()).containsExactly("11", "12");
            assertThat(ca.actions().stream().filter(x -> "OPEN_COMMUNITY_THREAD".equals(x.type()))
                    .map(x -> x.fallbackRoute()).toList())
                    .containsExactly("/community/posts/11", "/community/posts/12");
            assertThat(cardIn(cards, "urn:oid:T").communityThreads().postIds()).containsExactly("30");
            assertThat(cardIn(cards, "urn:oid:W").communityThreads()).isNull();
            assertThat(actionTypes(cardIn(cards, "urn:oid:W"))).doesNotContain("OPEN_COMMUNITY_THREAD");
        }

        @Test
        @DisplayName("an empty feed makes no lookup at all")
        void emptyFeedNoQuery() {
            ingest.setSnapshotForTest(List.of());
            feed.feedForViewer(LAT, LNG, null, null);
            verify(alertPostRepo, never()).findReadableThreadPosts(anyCollection(), anyString());
        }

        @Test
        @DisplayName("a failing lookup degrades to no threads, never to no feed")
        void lookupFailureIsNotFatal() {
            ingest.setSnapshotForTest(List.of(watch("Flood Watch").build()));
            when(alertPostRepo.findReadableThreadPosts(anyCollection(), anyString()))
                    .thenThrow(new IllegalStateException("db down"));
            List<AlertCardDto> cards = feed.feedForViewer(LAT, LNG, null, null).alerts();
            assertThat(cards).hasSize(1);
            assertThat(cards.get(0).communityThreads()).isNull();
        }
    }

    @Test
    @DisplayName("clusterKey is presentation-only: source, event, sorted zones, onset hour")
    void clusterKey() {
        AlertCardDto c = card(watch("Flood Watch").ugc(List.of("UTZ105", "UTZ104"))
                .startedAt("2026-10-13T12:34:00Z").build());
        assertThat(c.clusterKey()).isEqualTo("NWS:flood watch:UTZ104+UTZ105:2026-10-13T12");
    }
}
