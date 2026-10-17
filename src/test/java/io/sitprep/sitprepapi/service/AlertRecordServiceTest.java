package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.AlertHistory;
import io.sitprep.sitprepapi.domain.AlertSourceRevision;
import io.sitprep.sitprepapi.dto.AlertCardDto;
import io.sitprep.sitprepapi.dto.AlertFeedResponse;
import io.sitprep.sitprepapi.dto.AlertRecordResponse;
import io.sitprep.sitprepapi.repo.AlertHistoryRepo;
import io.sitprep.sitprepapi.repo.AlertSourceRevisionRepo;
import io.sitprep.sitprepapi.service.AlertIngestService.CapReference;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import io.sitprep.sitprepapi.util.AlertIdentity;
import io.sitprep.sitprepapi.util.AlertSourceFingerprint;
import io.sitprep.sitprepapi.util.Vtec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * S2 C4 — the canonical record lookup ({@code GET /api/alerts/record}) and the
 * batched lifecycle C5's inbox reads.
 *
 * <p>Fixed clocks throughout. The repositories are in-memory maps wearing the
 * repository interfaces, so every test can also say how many statements the
 * answer cost: the thread and the lifecycle batch grow with BATCHES (rounds,
 * chunks), never with the number of messages.</p>
 */
class AlertRecordServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** San Carlos AZ — TestAlerts' default zone AZZ560 is one of these. */
    private static final Set<String> SAN_CARLOS = Set.of("AZZ560", "AZC007", "AZZ133");
    private static final double LAT = 33.3172, LNG = -110.5297;
    /** Bend OR — far outside every San Carlos alert. */
    private static final double FAR_LAT = 44.05, FAR_LNG = -121.31;

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final String FUTURE = "2099-01-01T00:00:00Z";

    /** Yesterday's event, long over by NOW. */
    private static final String T1 = "2026-10-01T15:00:00Z";
    private static final String T2 = "2026-10-01T16:00:00Z";
    private static final String T3 = "2026-10-01T17:00:00Z";
    private static final String ENDED = "2026-10-01T18:00:00Z";

    private static final String FF_KEY = "KSLC.FF.W.0042.2026";

    private AlertDispatchService dispatch;
    private AlertIngestService ingest;
    private AlertFeedService feed;
    private AlertHistoryService history;
    private AlertRecordService records;

    private Map<String, AlertSourceRevision> revTable;
    private Map<String, AlertHistory> histTable;
    private AlertSourceRevisionRepo revisions;
    private AlertHistoryRepo historyRepo;
    private Snapshot live;

    @BeforeEach
    void setUp() {
        NwsZoneService zones = new NwsZoneService();
        zones.seedPointZones(LAT, LNG, SAN_CARLOS);
        dispatch = new AlertDispatchService(null, null, null, null, null, null, zones, null);
        dispatch.loadTemplates();

        ingest = mock(AlertIngestService.class);
        live = new Snapshot(List.of(), NOW, NOW);
        when(ingest.getSnapshot()).thenAnswer(inv -> live);
        when(ingest.zoneCodesForPoint(anyDouble(), anyDouble())).thenAnswer(inv -> {
            double lat = inv.getArgument(0);
            return Math.abs(lat - LAT) < 0.01 ? SAN_CARLOS : Set.of("ORZ506");
        });

        feed = new AlertFeedService(ingest, dispatch);
        feed.clock = Clock.fixed(NOW, ZoneOffset.UTC);

        revTable = new LinkedHashMap<>();
        histTable = new LinkedHashMap<>();
        revisions = inMemoryRevisions();
        historyRepo = inMemoryHistory();

        history = new AlertHistoryService(historyRepo, ingest, dispatch, feed);
        history.clock = Clock.fixed(NOW, ZoneOffset.UTC);
        records = new AlertRecordService(ingest, feed, history, historyRepo, revisions);
        records.clock = Clock.fixed(NOW, ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static Vtec vtec(String action, String key) {
        String[] p = key.split("\\.");
        return new Vtec(action, p[0], p[1], p[2], p[3], null, null, Integer.parseInt(p[4]));
    }

    private static CapReference ref(String id, String sent) {
        return new CapReference("w-nws.webmaster@noaa.gov", id, sent);
    }

    /** A live, guided Flood Warning at San Carlos — a template SitPrep has approved copy for. */
    private static NormalizedAlert guided(String id) {
        return TestAlerts.nws("Flood Warning").id(id).sent("2026-10-14T11:00:00Z").endsAt(FUTURE).build();
    }

    /** NEW → CON → EXP of one VTEC event, each naming every earlier message (as NWS does). */
    private static NormalizedAlert ffNew() {
        return TestAlerts.nws("Flood Warning").id("urn:oid:FF.NEW").sent(T1).endsAt(ENDED)
                .vtec(List.of(vtec("NEW", FF_KEY))).responseTypes(List.of("Evacuate")).build();
    }

    private static NormalizedAlert ffCon() {
        return TestAlerts.nws("Flood Warning").id("urn:oid:FF.CON").sent(T2).endsAt(ENDED)
                .messageType("Update").vtec(List.of(vtec("CON", FF_KEY)))
                .referenceTuples(List.of(ref("urn:oid:FF.NEW", T1))).build();
    }

    private static NormalizedAlert ffExp() {
        return TestAlerts.nws("Flood Warning").id("urn:oid:FF.EXP").sent(T3).endsAt(ENDED)
                .messageType("Update").vtec(List.of(vtec("EXP", FF_KEY)))
                .referenceTuples(List.of(ref("urn:oid:FF.CON", T2), ref("urn:oid:FF.NEW", T1))).build();
    }

    /** As the recorder writes it: one row per revision, payload once. */
    private void storeRevision(NormalizedAlert a, Instant seen) {
        AlertSourceRevision r = new AlertSourceRevision();
        String key = AlertIdentity.dedupeKey(a.source(), a.id());
        r.setRevisionRef(AlertSourceFingerprint.revisionRef(a));
        r.setDedupeKey(key);
        r.setSource(key.substring(0, key.indexOf(':')));
        r.setUpstreamId(a.id());
        r.setFingerprint(AlertSourceFingerprint.fingerprint(a));
        r.setVtecEventKeys(a.vtecEventKeys().isEmpty() ? null : String.join(" ", a.vtecEventKeys()));
        r.setReferenceIds(a.references().isEmpty() ? null : String.join(" ", a.references()));
        r.setSentAt(a.sent() == null ? null : Instant.parse(a.sent()));
        try {
            r.setPayload(MAPPER.writeValueAsString(a));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        r.setFirstSeenAt(seen);
        r.setLastSeenAt(seen);
        revTable.put(r.getRevisionRef(), r);
    }

    private void storeHistory(NormalizedAlert a, Instant seen) {
        AlertHistory row = history.toRow(a, seen);
        histTable.put(row.getAlertId(), row);
    }

    private void storeBoth(NormalizedAlert a, Instant seen) {
        storeRevision(a, seen);
        storeHistory(a, seen);
    }

    private void liveSnapshot(NormalizedAlert... alerts) {
        live = new Snapshot(List.of(alerts), NOW, NOW);
    }

    private static String key(NormalizedAlert a) {
        return AlertIdentity.dedupeKey(a.source(), a.id());
    }

    private static JsonNode json(Object o) {
        return MAPPER.valueToTree(o);
    }

    private AlertRecordResponse lookup(String key) {
        return records.lookup(key, null, LAT, LNG, null);
    }

    private static List<String> memberKeys(AlertRecordResponse r) {
        return r.thread().members().stream().map(AlertRecordResponse.Member::dedupeKey).toList();
    }

    private static Map<String, String> memberStates(AlertRecordResponse r) {
        return r.thread().members().stream().collect(Collectors.toMap(
                AlertRecordResponse.Member::dedupeKey, AlertRecordResponse.Member::state));
    }

    // ------------------------------------------------------------------
    // Resolution — every spelling of a link finds the same alert
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("resolve formats")
    class Resolve {

        @Test
        void sourceColonId_sourceDashId_andBareIdWithAHint_allFindTheSameLiveAlert() {
            NormalizedAlert a = guided("urn:oid:LIVE.1");
            liveSnapshot(a);

            for (AlertRecordResponse r : List.of(
                    records.lookup("NWS:urn:oid:LIVE.1", null, LAT, LNG, null),
                    records.lookup("NWS-urn:oid:LIVE.1", null, LAT, LNG, null),
                    records.lookup("nws:urn:oid:LIVE.1", null, LAT, LNG, null),
                    records.lookup("  urn:oid:LIVE.1 ", "nws", LAT, LNG, null))) {
                assertThat(r.status()).isEqualTo("live");
                assertThat(r.card().dedupeKey()).isEqualTo("NWS:urn:oid:LIVE.1");
            }
        }

        @Test
        void aBareIdWithNoHintIsAmbiguous_andNothingIsLookedUp() {
            liveSnapshot(guided("urn:oid:LIVE.1"));
            AlertRecordResponse r = records.lookup("urn:oid:LIVE.1", null, LAT, LNG, null);
            assertThat(r.status()).isEqualTo("ambiguous");
            assertThat(r.card()).isNull();
            assertThat(r.thread()).isNull();
            assertThat(r.meta()).as("still says what time the server answered at").isNotNull();
            verifyNoInteractions(revisions, historyRepo);
        }

        @Test
        void aPrefixThatContradictsTheHintIsAmbiguous() {
            liveSnapshot(guided("urn:oid:LIVE.1"));
            assertThat(records.lookup("USGS:urn:oid:LIVE.1", "NWS", LAT, LNG, null).status())
                    .isEqualTo("ambiguous");
        }

        @Test
        void anUnknownSourceHintOrAnUnheldIdIsNotFound() {
            liveSnapshot(guided("urn:oid:LIVE.1"));
            assertThat(records.lookup("urn:oid:LIVE.1", "NIFC", LAT, LNG, null).status()).isEqualTo("not_found");
            assertThat(records.lookup("NWS:urn:oid:NOPE", null, LAT, LNG, null).status()).isEqualTo("not_found");
            assertThat(records.lookup("   ", null, LAT, LNG, null).status()).isEqualTo("not_found");
        }

        @Test
        void theSameIdUnderAnotherSourceIsNotThisAlert() {
            // alert_history is keyed by the bare upstream id: a row for USGS id X
            // must not answer a link to NWS:X.
            storeHistory(TestAlerts.usgs("M 5.0").id("shared-id").build(), NOW.minusSeconds(60));
            assertThat(lookup("NWS:shared-id").status()).isEqualTo("not_found");
        }

        @Test
        void aRetractionIsNeverACard_soItsLinkIsNotFound() {
            NormalizedAlert cancel = TestAlerts.nws("Flood Warning").id("urn:oid:RETRACT")
                    .messageType("Cancel").references(List.of("urn:oid:X")).endsAt(FUTURE).build();
            liveSnapshot(cancel);
            storeBoth(cancel, NOW);
            assertThat(lookup("NWS:urn:oid:RETRACT").status()).isEqualTo("not_found");
        }
    }

    // ------------------------------------------------------------------
    // Live — re-evaluated now
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("live")
    class Live {

        @Test
        void aListedAlertIsTheLiveCard_currentWithItsPrimaryAction() {
            NormalizedAlert a = guided("urn:oid:LIVE.1");
            liveSnapshot(a);

            AlertRecordResponse r = lookup("NWS:urn:oid:LIVE.1");
            assertThat(r.status()).isEqualTo("live");
            assertThat(r.card().presentation().current()).isTrue();
            assertThat(r.card().interpreter().primaryAction())
                    .as("control: the live card does carry guidance").isNotNull();
            assertThat(r.card().viewerContext().matchType()).isEqualTo("zone");
            assertThat(r.card().viewerContext().containment()).isEqualTo("zone");
            assertThat(r.meta().lastSuccessAt()).isEqualTo(NOW.toString());
            assertThat(r.meta().serverTime()).isEqualTo(NOW.toString());
            assertThat(r.meta().coverageCaveat()).isEqualTo(AlertFeedResponse.COVERAGE_CAVEAT);
            assertThat(r.meta().context()).isEqualTo("current");
            assertThat(r.thread().threadKey()).isEqualTo(r.card().lifecycleInfo().threadKey());
            assertThat(memberKeys(r)).containsExactly("NWS:urn:oid:LIVE.1");
            assertThat(r.revisions()).containsExactly(AlertSourceFingerprint.revisionRef(a));
        }

        @Test
        void aListedAlertAlreadyReplacedInTheSnapshotIsLive_butItsCardIsARecord() {
            NormalizedAlert original = guided("urn:oid:OLD");
            NormalizedAlert update = TestAlerts.nws("Flood Warning").id("urn:oid:NEW").messageType("Update")
                    .sent("2026-10-14T11:30:00Z").references(List.of("urn:oid:OLD")).endsAt(FUTURE).build();
            liveSnapshot(original, update);

            AlertRecordResponse r = lookup("NWS:urn:oid:OLD");
            assertThat(r.status()).isEqualTo("live");
            assertThat(r.card().lifecycleState()).isEqualTo("superseded");
            assertThat(r.card().presentation().current()).isFalse();
            assertThat(r.card().interpreter().primaryAction()).isNull();
            assertThat(r.card().lifecycleInfo().replacedBy()).isEqualTo("NWS:urn:oid:NEW");
            assertThat(memberKeys(r)).containsExactly("NWS:urn:oid:OLD", "NWS:urn:oid:NEW");
        }

        @Test
        void anOutOfAreaLinkStillResolves_andSaysHonestlyThatItIsNotHere() {
            Map<String, Object> oregon = Map.of("type", "Polygon", "coordinates", List.of(List.of(
                    List.of(-121.5, 44.0), List.of(-121.0, 44.0), List.of(-121.0, 44.3),
                    List.of(-121.5, 44.3), List.of(-121.5, 44.0))));
            NormalizedAlert away = TestAlerts.nws("Flood Warning").id("urn:oid:AWAY")
                    .ugc(List.of("ORZ506")).same(List.of()).geometry(oregon).endsAt(FUTURE).build();
            liveSnapshot(away);

            AlertRecordResponse r = lookup("NWS:urn:oid:AWAY");
            assertThat(r.status()).isEqualTo("live");
            assertThat(r.card().location().matchType()).as("not relevant to this point").isNull();
            assertThat(r.card().viewerContext().matchType()).isNull();
            assertThat(r.card().viewerContext().containment()).isEqualTo("outside");
            assertThat(r.geometry()).isEqualTo(oregon);

            // A reader standing inside it is told so.
            AlertRecordResponse inside = records.lookup("NWS:urn:oid:AWAY", null, 44.1, -121.2, "home");
            assertThat(inside.card().viewerContext().containment()).isEqualTo("inside");
            assertThat(inside.card().viewerContext().role()).isEqualTo("HOUSEHOLD_LOCATION");
            assertThat(inside.meta().context()).isEqualTo("home");
        }

        @Test
        void aZoneOnlyOutOfAreaAlertNeverClaimsContainment() {
            NormalizedAlert zoneOnly = TestAlerts.nws("Flood Warning").id("urn:oid:ORZONE")
                    .ugc(List.of("ORZ506")).same(List.of()).endsAt(FUTURE).build();
            liveSnapshot(zoneOnly);
            AlertCardDto card = lookup("NWS:urn:oid:ORZONE").card();
            assertThat(card.viewerContext().matchType()).isNull();
            assertThat(card.viewerContext().containment()).isEqualTo("unknown");
        }

        @Test
        void withNoPointThereIsNoViewerContext() {
            liveSnapshot(guided("urn:oid:LIVE.1"));
            AlertRecordResponse r = records.lookup("NWS:urn:oid:LIVE.1", null, null, null, null);
            assertThat(r.status()).isEqualTo("live");
            assertThat(r.card().viewerContext()).isNull();
            assertThat(r.card().location().matchType()).isNull();
        }
    }

    // ------------------------------------------------------------------
    // Record — from source revisions / history, never guidance
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("record")
    class RecordPath {

        @Test
        void anExpiredLinkIsARecord_withNoPrimaryActionAndNoCurrentDirective() {
            storeBoth(ffNew(), NOW.minusSeconds(86_400 * 12));
            storeBoth(ffCon(), NOW.minusSeconds(86_400 * 12));
            storeBoth(ffExp(), NOW.minusSeconds(86_400 * 12));

            AlertRecordResponse r = lookup("NWS:urn:oid:FF.EXP");
            assertThat(r.status()).isEqualTo("record");
            AlertCardDto card = r.card();
            assertThat(card.lifecycleState()).isEqualTo("expired");
            assertThat(card.lifecycleInfo().reason()).isEqualTo("vtec_exp");
            assertThat(card.presentation().current()).isFalse();
            assertThat(card.presentation().priority()).isEqualTo("record");
            assertThat(card.presentation().tone()).isEqualTo("neutral");
            assertThat(card.interpreter().primaryAction()).isNull();
            assertThat(card.whatToDo()).isNull();
            assertThat(card.precautions()).isNull();
            assertThat(json(card).path("safety").path("movementDirective").asText()).isEqualTo("none");
            assertThat(card.actions()).extracting(AlertCardDto.Action::type)
                    .doesNotContain("OPEN_HOUSEHOLD_CHECKIN", "OPEN_EMERGENCY_PLAN", "START_POST");
        }

        @Test
        void anOldOrderIsARecord_itsAsIssuedDirectiveSurvivesButNothingIsCurrent() {
            // ffNew() carried Evacuate. Replaced since; the link still resolves.
            storeBoth(ffNew(), NOW.minusSeconds(86_400 * 12));
            storeBoth(ffCon(), NOW.minusSeconds(86_400 * 12));

            AlertCardDto card = lookup("NWS:urn:oid:FF.NEW").card();
            JsonNode safety = json(card).path("safety");
            assertThat(safety.path("movementDirective").asText()).isEqualTo("none");
            assertThat(safety.path("asIssuedMovementDirective").asText()).isEqualTo("evacuate");
            assertThat(card.interpreter().primaryAction()).isNull();
            assertThat(card.isLifeThreatening()).isFalse();
        }

        @Test
        void aSupersededLinkPointsHonestlyAtWhatReplacedIt() {
            NormalizedAlert original = TestAlerts.nws("Flood Warning").id("urn:oid:S1")
                    .sent("2026-10-14T09:00:00Z").endsAt(FUTURE).build();
            NormalizedAlert update = TestAlerts.nws("Flood Warning").id("urn:oid:S2").messageType("Update")
                    .sent("2026-10-14T10:00:00Z").references(List.of("urn:oid:S1")).endsAt(FUTURE).build();
            storeBoth(original, NOW.minusSeconds(7_200));
            storeBoth(update, NOW.minusSeconds(60));
            liveSnapshot(update);   // NWS dropped the original when it replaced it

            AlertRecordResponse r = lookup("NWS:urn:oid:S1");
            assertThat(r.status()).isEqualTo("record");
            assertThat(r.card().lifecycleState()).isEqualTo("superseded");
            assertThat(r.card().lifecycleInfo().replacedBy()).isEqualTo("NWS:urn:oid:S2");
            assertThat(r.card().lifecycleInfo().verified()).isTrue();
            assertThat(r.card().interpreter().primaryAction()).isNull();
            assertThat(memberKeys(r)).containsExactly("NWS:urn:oid:S1", "NWS:urn:oid:S2");
            assertThat(memberStates(r)).containsEntry("NWS:urn:oid:S1", "superseded")
                    .containsEntry("NWS:urn:oid:S2", "updated");
        }

        @Test
        void anAllClearEndsItAsAllClear_aCancelAsCapCancel() {
            NormalizedAlert a1 = TestAlerts.nws("Flood Warning").id("urn:oid:AC1")
                    .sent("2026-10-14T08:00:00Z").endsAt(FUTURE).build();
            NormalizedAlert allClear = TestAlerts.nws("Flood Warning").id("urn:oid:AC1-END").messageType("Update")
                    .response("AllClear").sent("2026-10-14T09:00:00Z").references(List.of("urn:oid:AC1"))
                    .endsAt(FUTURE).build();
            NormalizedAlert a2 = TestAlerts.nws("Flood Warning").id("urn:oid:CC1")
                    .sent("2026-10-14T08:00:00Z").endsAt(FUTURE).build();
            NormalizedAlert cancel = TestAlerts.nws("Flood Warning").id("urn:oid:CC1-END").messageType("Cancel")
                    .sent("2026-10-14T09:00:00Z").references(List.of("urn:oid:CC1")).endsAt(FUTURE).build();
            for (NormalizedAlert a : List.of(a1, allClear, a2, cancel)) storeBoth(a, NOW.minusSeconds(3_600));

            AlertCardDto clear = lookup("NWS:urn:oid:AC1").card();
            assertThat(clear.lifecycleState()).isEqualTo("cancelled");
            assertThat(clear.lifecycleInfo().reason()).isEqualTo("all_clear");
            assertThat(clear.presentation().tone()).isEqualTo("all_clear");

            AlertCardDto cancelled = lookup("NWS:urn:oid:CC1").card();
            assertThat(cancelled.lifecycleState()).isEqualTo("cancelled");
            assertThat(cancelled.lifecycleInfo().reason()).isEqualTo("cap_cancel");
            assertThat(cancelled.presentation().tone()).isEqualTo("neutral");
        }

        @Test
        void aRevisionOnlyRecordThatIsNoLongerListedSaysSo_unverified_neverCancelled() {
            // No history row; only the live snapshot can say "still listed", and
            // it does not list it. The revision's own lastSeenAt is RECENT — it is
            // retention-grade and must not be read as a sighting.
            NormalizedAlert gone = TestAlerts.nws("Flood Warning").id("urn:oid:GONE")
                    .sent("2026-10-14T09:00:00Z").endsAt(FUTURE).responseTypes(List.of("Evacuate")).build();
            storeRevision(gone, NOW.minusSeconds(30));

            AlertRecordResponse r = lookup("NWS:urn:oid:GONE");
            assertThat(r.status()).isEqualTo("record");
            assertThat(r.card().lifecycleState()).isEqualTo("no_longer_listed");
            assertThat(r.card().lifecycleInfo().verified()).isFalse();
            assertThat(r.card().lifecycleInfo().current()).isFalse();
            assertThat(r.card().interpreter().primaryAction()).isNull();
        }

        @Test
        void aRecordIsJudgedByTheHistoryRowsSightings_theSameAnswerHistoryGives() {
            // History row last seen an hour before the latest successful poll.
            NormalizedAlert gone = TestAlerts.nws("Flood Warning").id("urn:oid:HGONE")
                    .sent("2026-10-14T09:00:00Z").endsAt(FUTURE).build();
            storeBoth(gone, NOW.minusSeconds(3_600));
            assertThat(lookup("NWS:urn:oid:HGONE").card().lifecycleState()).isEqualTo("no_longer_listed");
            AlertCardDto fromHistory = history.historyFor(LAT, LNG, 30).alerts().stream()
                    .filter(c -> "urn:oid:HGONE".equals(c.id())).findFirst().orElseThrow();
            assertThat(fromHistory.lifecycleState()).isEqualTo("no_longer_listed");
        }

        @Test
        void aRecordFromHistoryAloneStillResolves_preV110Rows() {
            NormalizedAlert old = TestAlerts.nws("Flood Warning").id("urn:oid:PRE")
                    .sent(T1).endsAt(ENDED).build();
            storeHistory(old, NOW.minusSeconds(86_400 * 10));
            AlertRecordResponse r = lookup("NWS-urn:oid:PRE");
            assertThat(r.status()).isEqualTo("record");
            assertThat(r.card().lifecycleState()).isEqualTo("expired");
            assertThat(r.revisions()).containsExactly(AlertSourceFingerprint.revisionRef(old));
        }

        @Test
        void aUsgsQuakeRevisedUnderOneIdListsItsRevisionsNewestFirst_andShowsTheNewest() {
            Map<String, Object> point = Map.of("type", "Point", "coordinates", List.of(-110.5, 33.3, 10.0));
            NormalizedAlert first = TestAlerts.usgs("M 5.9 - near San Carlos").id("us7000q1")
                    .sent("2026-10-14T08:00:00Z").geometry(point).magnitude(5.9)
                    .revisionUpdatedAt("2026-10-14T08:05:00Z").build();
            NormalizedAlert revised = TestAlerts.usgs("M 6.2 - near San Carlos").id("us7000q1")
                    .sent("2026-10-14T08:00:00Z").geometry(point).magnitude(6.2)
                    .revisionUpdatedAt("2026-10-14T09:00:00Z").build();
            storeRevision(first, NOW.minusSeconds(7_200));
            storeRevision(revised, NOW.minusSeconds(3_600));

            AlertRecordResponse r = lookup("USGS:us7000q1");
            assertThat(r.status()).isEqualTo("record");
            assertThat(r.revisions()).containsExactly(
                    AlertSourceFingerprint.revisionRef(revised), AlertSourceFingerprint.revisionRef(first));
            assertThat(r.card().detail().earthquake().magnitude()).isEqualTo(6.2);
            assertThat(r.thread().threadKey()).isEqualTo("USGS:us7000q1");
            assertThat(memberKeys(r)).containsExactly("USGS:us7000q1");
            assertThat(r.thread().members().get(0).revisionRef())
                    .isEqualTo(AlertSourceFingerprint.revisionRef(revised));
        }
    }

    // ------------------------------------------------------------------
    // Thread membership
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("thread")
    class ThreadMembership {

        @Test
        void aVtecThreadHoldsEveryMessageOfTheEvent_oldestFirst_withEachOnesState() {
            storeBoth(ffNew(), NOW.minusSeconds(86_400 * 12));
            storeBoth(ffCon(), NOW.minusSeconds(86_400 * 12));
            storeBoth(ffExp(), NOW.minusSeconds(86_400 * 12));

            AlertRecordResponse r = lookup("NWS:urn:oid:FF.NEW");
            assertThat(r.thread().threadKey()).isEqualTo("VTEC:" + FF_KEY);
            assertThat(memberKeys(r)).containsExactly(
                    "NWS:urn:oid:FF.NEW", "NWS:urn:oid:FF.CON", "NWS:urn:oid:FF.EXP");
            assertThat(r.thread().members()).extracting(AlertRecordResponse.Member::state)
                    .containsExactly("superseded", "superseded", "expired");
            assertThat(r.thread().members()).extracting(AlertRecordResponse.Member::sent)
                    .containsExactly(T1, T2, T3);
            assertThat(r.thread().members()).extracting(AlertRecordResponse.Member::revisionRef)
                    .containsExactly(AlertSourceFingerprint.revisionRef(ffNew()),
                            AlertSourceFingerprint.revisionRef(ffCon()),
                            AlertSourceFingerprint.revisionRef(ffExp()));
            // NEW was replaced by the CON — its immediate successor, not the EXP
            // that also names it.
            assertThat(r.card().lifecycleInfo().replacedBy()).isEqualTo("NWS:urn:oid:FF.CON");
        }

        @Test
        void vtecAloneThreadsMessagesThatNameNoReferences() {
            String k = "KSLC.FL.W.0007.2026";
            NormalizedAlert n = TestAlerts.nws("Flood Warning").id("urn:oid:V1").sent(T1).endsAt(ENDED)
                    .vtec(List.of(vtec("NEW", k))).build();
            NormalizedAlert c = TestAlerts.nws("Flood Warning").id("urn:oid:V2").sent(T2).endsAt(ENDED)
                    .messageType("Update").vtec(List.of(vtec("CON", k))).build();
            NormalizedAlert e = TestAlerts.nws("Flood Warning").id("urn:oid:V3").sent(T3).endsAt(ENDED)
                    .messageType("Update").vtec(List.of(vtec("EXP", k))).build();
            for (NormalizedAlert a : List.of(n, c, e)) storeBoth(a, NOW.minusSeconds(86_400 * 12));

            assertThat(memberKeys(lookup("NWS:urn:oid:V2")))
                    .containsExactly("NWS:urn:oid:V1", "NWS:urn:oid:V2", "NWS:urn:oid:V3");
        }

        @Test
        void anUpgradedWatchsThreadHoldsTheMessageThatUpgradedIt() {
            String watchKey = "KSLC.FF.A.0007.2026";
            String warnKey = "KSLC.FF.W.0050.2026";
            NormalizedAlert watch = TestAlerts.nwsWatch("Flood Watch").id("urn:oid:W1")
                    .sent("2026-10-14T08:00:00Z").endsAt(FUTURE).vtec(List.of(vtec("NEW", watchKey))).build();
            NormalizedAlert upgrade = TestAlerts.nws("Flood Warning").id("urn:oid:U1")
                    .sent("2026-10-14T10:00:00Z").endsAt(FUTURE)
                    .vtec(List.of(vtec("UPG", watchKey), vtec("NEW", warnKey)))
                    .referenceTuples(List.of(ref("urn:oid:W1", "2026-10-14T08:00:00Z"))).build();
            storeBoth(watch, NOW.minusSeconds(7_200));
            storeBoth(upgrade, NOW.minusSeconds(60));
            liveSnapshot(upgrade);

            AlertRecordResponse w = lookup("NWS:urn:oid:W1");
            assertThat(w.status()).isEqualTo("record");
            assertThat(w.thread().threadKey()).isEqualTo("VTEC:" + watchKey);
            assertThat(memberKeys(w)).containsExactly("NWS:urn:oid:W1", "NWS:urn:oid:U1");
            assertThat(w.card().lifecycleState()).isEqualTo("superseded");
            assertThat(w.card().lifecycleInfo().reason()).isEqualTo("vtec_upg");
            assertThat(w.card().lifecycleInfo().replacedBy()).isEqualTo("NWS:urn:oid:U1");
            assertThat(w.card().presentation().tone()).as("a watch record is never danger").isEqualTo("neutral");
            assertThat(memberStates(w)).containsEntry("NWS:urn:oid:U1", "active");

            // The warning's own thread is the warning's event; the watch is not in it.
            AlertRecordResponse u = lookup("NWS:urn:oid:U1");
            assertThat(u.status()).isEqualTo("live");
            assertThat(u.thread().threadKey()).isEqualTo("VTEC:" + warnKey);
            assertThat(memberKeys(u)).containsExactly("NWS:urn:oid:U1");
        }

        @Test
        void aCapChainIsFollowedHopByHop_whenEachMessageNamesOnlyItsPredecessor() {
            NormalizedAlert x = TestAlerts.nws("Special Weather Statement").id("urn:oid:X").sent(T1).endsAt(ENDED).build();
            NormalizedAlert y = TestAlerts.nws("Special Weather Statement").id("urn:oid:Y").sent(T2).endsAt(ENDED)
                    .messageType("Update").referenceTuples(List.of(ref("urn:oid:X", T1))).build();
            NormalizedAlert z = TestAlerts.nws("Special Weather Statement").id("urn:oid:Z").sent(T3).endsAt(ENDED)
                    .messageType("Update").referenceTuples(List.of(ref("urn:oid:Y", T2))).build();
            for (NormalizedAlert a : List.of(x, y, z)) storeBoth(a, NOW.minusSeconds(86_400 * 12));

            for (String k : List.of("NWS:urn:oid:X", "NWS:urn:oid:Y", "NWS:urn:oid:Z")) {
                assertThat(memberKeys(lookup(k))).as("from %s", k)
                        .containsExactly("NWS:urn:oid:X", "NWS:urn:oid:Y", "NWS:urn:oid:Z");
            }
            assertThat(lookup("NWS:urn:oid:X").thread().threadKey()).isEqualTo("CAP:urn:oid:X");
            assertThat(memberStates(lookup("NWS:urn:oid:Z")))
                    .containsEntry("NWS:urn:oid:X", "superseded")
                    .containsEntry("NWS:urn:oid:Y", "superseded")
                    .containsEntry("NWS:urn:oid:Z", "expired");
        }

        @Test
        void aCapChainLongerThanTheRoundCap_saysItIsTruncated_aShortOneDoesNot() {
            // Build-10 backlog: the walk stopped at MAX_THREAD_ROUNDS silently.
            // Membership is unchanged: the rounds' members, and a flag.
            List<NormalizedAlert> chain = new java.util.ArrayList<>();
            for (int i = 0; i < AlertRecordService.MAX_THREAD_ROUNDS + 3; i++) {
                String sent = Instant.parse(T1).plusSeconds(60L * i).toString();
                var b = TestAlerts.nws("Special Weather Statement").id("urn:oid:LONG." + i).sent(sent).endsAt(ENDED);
                if (i > 0) {
                    NormalizedAlert prev = chain.get(i - 1);
                    b = b.messageType("Update").referenceTuples(List.of(ref(prev.id(), prev.sent())));
                }
                chain.add(b.build());
            }
            for (NormalizedAlert a : chain) storeBoth(a, NOW.minusSeconds(86_400 * 12));

            AlertRecordResponse longOne = lookup("NWS:urn:oid:LONG.0");
            assertThat(longOne.thread().truncated()).isTrue();
            assertThat(memberKeys(longOne)).hasSizeLessThan(chain.size());

            NormalizedAlert x = TestAlerts.nws("Special Weather Statement").id("urn:oid:SX").sent(T1).endsAt(ENDED).build();
            NormalizedAlert y = TestAlerts.nws("Special Weather Statement").id("urn:oid:SY").sent(T2).endsAt(ENDED)
                    .messageType("Update").referenceTuples(List.of(ref("urn:oid:SX", T1))).build();
            storeBoth(x, NOW.minusSeconds(86_400 * 12));
            storeBoth(y, NOW.minusSeconds(86_400 * 12));
            assertThat(lookup("NWS:urn:oid:SX").thread().truncated()).isNull();
        }

        @Test
        void aChainIsFollowedThroughALiveOnlyMessage_toTheStoredOneThatNamesIt() {
            // X stored; Y is live only (not yet recorded) and names X; Z is stored
            // and names only Y. Nothing stored names X, so a walk that expanded
            // only stored rows would stop at Y and never reach Z.
            NormalizedAlert x = TestAlerts.nws("Special Weather Statement").id("urn:oid:LX").sent(T1).endsAt(FUTURE).build();
            NormalizedAlert y = TestAlerts.nws("Special Weather Statement").id("urn:oid:LY").sent(T2).endsAt(FUTURE)
                    .messageType("Update").referenceTuples(List.of(ref("urn:oid:LX", T1))).build();
            NormalizedAlert z = TestAlerts.nws("Special Weather Statement").id("urn:oid:LZ").sent(T3).endsAt(FUTURE)
                    .messageType("Update").referenceTuples(List.of(ref("urn:oid:LY", T2))).build();
            storeBoth(x, NOW.minusSeconds(3_600));
            storeBoth(z, NOW.minusSeconds(60));   // recorded a minute ago; NWS has since dropped it
            liveSnapshot(y);

            AlertRecordResponse r = lookup("NWS:urn:oid:LX");
            assertThat(memberKeys(r)).containsExactly("NWS:urn:oid:LX", "NWS:urn:oid:LY", "NWS:urn:oid:LZ");
            // A listed member reads exactly as the feed reads it — the snapshot's
            // own edges — so the lookup never contradicts the live card.
            assertThat(memberStates(r)).containsEntry("NWS:urn:oid:LX", "superseded")
                    .containsEntry("NWS:urn:oid:LY", "updated")
                    .containsEntry("NWS:urn:oid:LZ", "updated");
        }

        @Test
        void branching_oneMessageReplacedByTwoSegments_holdsAllThree() {
            NormalizedAlert x = TestAlerts.nws("Special Weather Statement").id("urn:oid:BX").sent(T1).endsAt(FUTURE).build();
            NormalizedAlert y1 = TestAlerts.nws("Special Weather Statement").id("urn:oid:BY1").sent(T2).endsAt(FUTURE)
                    .messageType("Update").referenceTuples(List.of(ref("urn:oid:BX", T1))).build();
            NormalizedAlert y2 = TestAlerts.nws("Special Weather Statement").id("urn:oid:BY2").sent(T2).endsAt(FUTURE)
                    .messageType("Update").ugc(List.of("AZZ133")).referenceTuples(List.of(ref("urn:oid:BX", T1))).build();
            for (NormalizedAlert a : List.of(x, y1, y2)) storeBoth(a, NOW.minusSeconds(3_600));
            liveSnapshot(y1, y2);

            AlertRecordResponse r = lookup("NWS:urn:oid:BX");
            assertThat(memberKeys(r)).containsExactly("NWS:urn:oid:BX", "NWS:urn:oid:BY1", "NWS:urn:oid:BY2");
            assertThat(r.card().lifecycleState()).isEqualTo("superseded");
            // Two successors sent together: the answer does not depend on row order.
            assertThat(r.card().lifecycleInfo().replacedBy()).isEqualTo("NWS:urn:oid:BY1");
            assertThat(memberStates(r)).containsEntry("NWS:urn:oid:BY1", "updated")
                    .containsEntry("NWS:urn:oid:BY2", "updated");
        }

        @Test
        void multiParent_oneMessageReplacingTwo_holdsAllThree() {
            NormalizedAlert a = TestAlerts.nws("Special Weather Statement").id("urn:oid:PA").sent(T1).endsAt(FUTURE).build();
            NormalizedAlert b = TestAlerts.nws("Special Weather Statement").id("urn:oid:PB").sent(T1).endsAt(FUTURE)
                    .ugc(List.of("AZZ133")).build();
            NormalizedAlert merged = TestAlerts.nws("Special Weather Statement").id("urn:oid:PM").sent(T2).endsAt(FUTURE)
                    .messageType("Update")
                    .referenceTuples(List.of(ref("urn:oid:PA", T1), ref("urn:oid:PB", T1))).build();
            for (NormalizedAlert m : List.of(a, b, merged)) storeBoth(m, NOW.minusSeconds(3_600));
            liveSnapshot(merged);

            for (String k : List.of("NWS:urn:oid:PA", "NWS:urn:oid:PB", "NWS:urn:oid:PM")) {
                assertThat(memberKeys(lookup(k))).as("from %s", k)
                        .containsExactlyInAnyOrder("NWS:urn:oid:PA", "NWS:urn:oid:PB", "NWS:urn:oid:PM");
            }
            assertThat(lookup("NWS:urn:oid:PB").card().lifecycleInfo().replacedBy()).isEqualTo("NWS:urn:oid:PM");
        }

        @Test
        void anUnresolvedReferenceIsNotAMember_andTheCardSaysItIsNotInHand() {
            NormalizedAlert x = TestAlerts.nws("Special Weather Statement").id("urn:oid:UX").sent(T1).endsAt(FUTURE).build();
            NormalizedAlert y = TestAlerts.nws("Special Weather Statement").id("urn:oid:UY").sent(T2).endsAt(FUTURE)
                    .messageType("Update")
                    .referenceTuples(List.of(ref("urn:oid:NEVER-SEEN", "2026-09-30T00:00:00Z"), ref("urn:oid:UX", T1)))
                    .build();
            storeBoth(x, NOW.minusSeconds(3_600));
            storeBoth(y, NOW.minusSeconds(60));
            liveSnapshot(y);

            AlertRecordResponse r = lookup("NWS:urn:oid:UY");
            assertThat(memberKeys(r)).containsExactly("NWS:urn:oid:UX", "NWS:urn:oid:UY");
            Map<String, Boolean> resolved = r.card().lifecycleInfo().references().stream().collect(
                    Collectors.toMap(AlertCardDto.Reference::identifier, AlertCardDto.Reference::resolved));
            assertThat(resolved).containsEntry("urn:oid:NEVER-SEEN", false).containsEntry("urn:oid:UX", true);

            // The record side too: the old message, looked up, names its successor.
            AlertRecordResponse old = lookup("NWS:urn:oid:UX");
            assertThat(old.card().lifecycleInfo().replacedBy()).isEqualTo("NWS:urn:oid:UY");
        }
    }

    // ------------------------------------------------------------------
    // Query bounds
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("query count")
    class QueryCount {

        private void vtecThread(String prefix, int n) {
            String k = "KSLC.FL.W." + String.format("%04d", n) + ".2026";
            List<CapReference> earlier = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String id = "urn:oid:" + prefix + i;
                String sent = Instant.parse(T1).plusSeconds(60L * i).toString();
                NormalizedAlert m = TestAlerts.nws("Flood Warning").id(id).sent(sent).endsAt(ENDED)
                        .messageType(i == 0 ? "Alert" : "Update")
                        .vtec(List.of(vtec(i == 0 ? "NEW" : i == n - 1 ? "EXP" : "CON", k)))
                        .referenceTuples(List.copyOf(earlier)).build();
                storeBoth(m, NOW.minusSeconds(86_400 * 12));
                earlier.add(0, ref(id, sent));
            }
        }

        private int calls(Object mock, String method) {
            return (int) mockingDetails(mock).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().equals(method)).count();
        }

        @Test
        void aThreadOfThirtyCostsWhatAThreadOfThreeCosts() {
            vtecThread("SMALL", 3);
            vtecThread("BIG", 30);

            clearInvocations(revisions, historyRepo);
            AlertRecordResponse small = lookup("NWS:urn:oid:SMALL0");
            int smallRev = mockingDetails(revisions).getInvocations().size();
            int smallHist = mockingDetails(historyRepo).getInvocations().size();
            assertThat(small.thread().members()).hasSize(3);

            clearInvocations(revisions, historyRepo);
            AlertRecordResponse big = lookup("NWS:urn:oid:BIG0");
            assertThat(big.thread().members()).hasSize(30);
            assertThat(mockingDetails(revisions).getInvocations().size()).isEqualTo(smallRev);
            assertThat(mockingDetails(historyRepo).getInvocations().size()).isEqualTo(smallHist);

            // And the absolute bound: one revisions read, a bounded number of
            // thread rounds, at most two history reads.
            assertThat(calls(revisions, "findByDedupeKeyIn")).isEqualTo(1);
            assertThat(calls(revisions, "findThreadCandidates")).isBetween(1, AlertRecordService.MAX_THREAD_ROUNDS);
            assertThat(calls(historyRepo, "findAllById") + calls(historyRepo, "findById")).isLessThanOrEqualTo(2);
        }

        @Test
        void theLifecycleBatchCostsTheSameForThreeRowsAsForThirty() {
            vtecThread("P", 30);
            List<String> three = List.of("NWS:urn:oid:P0", "NWS:urn:oid:P1", "NWS:urn:oid:P2");
            List<String> thirty = new ArrayList<>();
            for (int i = 0; i < 30; i++) thirty.add("NWS:urn:oid:P" + i);

            clearInvocations(revisions, historyRepo);
            assertThat(records.lifecycleFor(three)).hasSize(3);
            int rev3 = mockingDetails(revisions).getInvocations().size();
            int hist3 = mockingDetails(historyRepo).getInvocations().size();

            clearInvocations(revisions, historyRepo);
            assertThat(records.lifecycleFor(thirty)).hasSize(30);
            assertThat(mockingDetails(revisions).getInvocations().size()).isEqualTo(rev3);
            assertThat(mockingDetails(historyRepo).getInvocations().size()).isEqualTo(hist3);
            assertThat(calls(revisions, "findByDedupeKeyIn")).isEqualTo(1);
            assertThat(calls(revisions, "findThreadCandidates")).isEqualTo(1);
            assertThat(calls(historyRepo, "findAllById")).isEqualTo(1);
        }

        @Test
        void anAllLiveBatchTouchesNoTable() {
            liveSnapshot(guided("urn:oid:L1"), guided("urn:oid:L2"));
            assertThat(records.lifecycleFor(List.of("NWS:urn:oid:L1", "NWS:urn:oid:L2"))).hasSize(2);
            verifyNoInteractions(revisions, historyRepo);
        }
    }

    // ------------------------------------------------------------------
    // lifecycleFor — the batch C5's inbox page calls
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("lifecycleFor")
    class LifecycleBatch {

        @Test
        void everyRefSpellingAnswers_keyedByTheRefAsAsked() {
            NormalizedAlert l = guided("urn:oid:L1");
            liveSnapshot(l);
            storeBoth(ffNew(), NOW.minusSeconds(86_400 * 12));
            storeBoth(ffCon(), NOW.minusSeconds(86_400 * 12));
            storeBoth(ffExp(), NOW.minusSeconds(86_400 * 12));
            String newRev = AlertSourceFingerprint.revisionRef(ffNew());

            Map<String, AlertRecordService.Lifecycle> out = records.lifecycleFor(List.of(
                    "NWS:urn:oid:L1", newRev, "NWS-urn:oid:FF.CON", "NWS:urn:oid:FF.EXP",
                    "NWS:urn:oid:NOPE", "urn:oid:bare-is-ambiguous", ""));

            assertThat(out).containsOnlyKeys("NWS:urn:oid:L1", newRev, "NWS-urn:oid:FF.CON", "NWS:urn:oid:FF.EXP");

            AlertRecordService.Lifecycle liveOne = out.get("NWS:urn:oid:L1");
            assertThat(liveOne.listed()).isTrue();
            assertThat(liveOne.current()).isTrue();
            assertThat(liveOne.state()).isEqualTo("active");
            assertThat(liveOne.dedupeKey()).isEqualTo("NWS:urn:oid:L1");
            assertThat(liveOne.revisionRef()).isEqualTo(AlertSourceFingerprint.revisionRef(l));

            AlertRecordService.Lifecycle replaced = out.get(newRev);
            assertThat(replaced.listed()).isFalse();
            assertThat(replaced.current()).isFalse();
            assertThat(replaced.state()).isEqualTo("superseded");
            assertThat(replaced.threadKey()).isEqualTo("VTEC:" + FF_KEY);
            assertThat(replaced.info().replacedBy()).isEqualTo("NWS:urn:oid:FF.CON");
            assertThat(replaced.dedupeKey()).isEqualTo("NWS:urn:oid:FF.NEW");

            assertThat(out.get("NWS-urn:oid:FF.CON").state()).isEqualTo("superseded");
            assertThat(out.get("NWS:urn:oid:FF.EXP").state()).isEqualTo("expired");
            assertThat(out.get("NWS:urn:oid:FF.EXP").info().verified()).isTrue();
        }

        @Test
        void aMessageThatEndedItIsFoundWhereverItIs_storedOrLive() {
            NormalizedAlert a = TestAlerts.nws("Flood Warning").id("urn:oid:E1")
                    .sent("2026-10-14T08:00:00Z").endsAt(FUTURE).build();
            NormalizedAlert cancel = TestAlerts.nws("Flood Warning").id("urn:oid:E1-CAN").messageType("Cancel")
                    .sent("2026-10-14T09:00:00Z").references(List.of("urn:oid:E1")).endsAt(FUTURE).build();
            storeBoth(a, NOW.minusSeconds(7_200));
            liveSnapshot(cancel);   // only live: not yet recorded

            AlertRecordService.Lifecycle l = records.lifecycleFor(List.of("NWS:urn:oid:E1")).get("NWS:urn:oid:E1");
            assertThat(l.state()).isEqualTo("cancelled");
            assertThat(l.info().reason()).isEqualTo("cap_cancel");
            assertThat(l.current()).isFalse();
        }

        @Test
        void listedIsTheLiveSnapshotsAnswer_neverTheRevisionsLastSeen() {
            NormalizedAlert recentRow = TestAlerts.nws("Flood Warning").id("urn:oid:R1")
                    .sent("2026-10-14T09:00:00Z").endsAt(FUTURE).build();
            storeRevision(recentRow, NOW);   // lastSeenAt == now, yet not listed
            NormalizedAlert oldRow = guided("urn:oid:R2");
            storeRevision(oldRow, NOW.minusSeconds(86_400 * 20));   // stale lastSeenAt, yet listed
            liveSnapshot(oldRow);

            Map<String, AlertRecordService.Lifecycle> out =
                    records.lifecycleFor(List.of("NWS:urn:oid:R1", "NWS:urn:oid:R2"));
            assertThat(out.get("NWS:urn:oid:R1").listed()).isFalse();
            assertThat(out.get("NWS:urn:oid:R1").state()).isEqualTo("no_longer_listed");
            assertThat(out.get("NWS:urn:oid:R1").current()).isFalse();
            assertThat(out.get("NWS:urn:oid:R2").listed()).isTrue();
            assertThat(out.get("NWS:urn:oid:R2").current()).isTrue();
        }

        @Test
        void unknownWhenNoPollHasSucceeded_theAlertsOwnFieldsStand() {
            live = new Snapshot(List.of(), NOW, null, Map.of(), Set.of());
            NormalizedAlert a = TestAlerts.nws("Flood Warning").id("urn:oid:COLD")
                    .sent("2026-10-14T09:00:00Z").endsAt(FUTURE).build();
            storeRevision(a, NOW.minusSeconds(60));
            AlertRecordService.Lifecycle l = records.lifecycleFor(List.of("NWS:urn:oid:COLD")).get("NWS:urn:oid:COLD");
            assertThat(l.state()).as("absence proves nothing before a poll has succeeded").isEqualTo("active");
            assertThat(l.listed()).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // In-memory repositories
    // ------------------------------------------------------------------

    private AlertSourceRevisionRepo inMemoryRevisions() {
        AlertSourceRevisionRepo r = mock(AlertSourceRevisionRepo.class);
        when(r.findByDedupeKeyIn(any())).thenAnswer(inv -> {
            Collection<String> keys = inv.getArgument(0);
            return revTable.values().stream().filter(x -> keys.contains(x.getDedupeKey())).toList();
        });
        when(r.findThreadCandidates(any(), any(), any())).thenAnswer(inv -> {
            Collection<String> vtec = inv.getArgument(0);
            Collection<String> refs = inv.getArgument(1);
            Collection<String> keys = inv.getArgument(2);
            return revTable.values().stream().filter(x ->
                    tokens(x.getVtecEventKeys()).stream().anyMatch(vtec::contains)
                            || tokens(x.getReferenceIds()).stream().anyMatch(refs::contains)
                            || keys.contains(x.getDedupeKey())).toList();
        });
        return r;
    }

    private static List<String> tokens(String spaced) {
        return spaced == null || spaced.isBlank() ? List.of() : List.of(spaced.trim().split(" +"));
    }

    private AlertHistoryRepo inMemoryHistory() {
        AlertHistoryRepo r = mock(AlertHistoryRepo.class);
        when(r.findAllById(any())).thenAnswer(inv -> {
            Iterable<String> ids = inv.getArgument(0);
            List<AlertHistory> out = new ArrayList<>();
            for (String id : ids) {
                AlertHistory row = histTable.get(id);
                if (row != null) out.add(row);
            }
            return out;
        });
        when(r.findById(any())).thenAnswer(inv -> java.util.Optional.ofNullable(histTable.get(inv.<String>getArgument(0))));
        when(r.findCandidates(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), any()))
                .thenAnswer(inv -> List.copyOf(histTable.values()));
        when(r.findRecordingSince()).thenReturn(null);
        return r;
    }
}
