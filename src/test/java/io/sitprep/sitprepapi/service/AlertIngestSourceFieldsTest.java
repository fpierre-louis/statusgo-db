package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.service.AlertIngestService.CapReference;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.util.Vtec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What ingest now keeps from each source (S2 contract §2, unit C1).
 *
 * <p>Each field below was on the wire and dropped at the normalizer: the NWS
 * office name, the four raw CAP timings, the whole CAP reference tuple, the
 * parsed VTEC thread; the USGS revision stamp, PAGER level and tsunami flag;
 * the FEMA declaration and incident dates. NWS is read from the captured
 * 2026-08-22 feed; USGS and FEMA from canned bodies at a pinned clock.</p>
 */
class AlertIngestSourceFieldsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static List<NormalizedAlert> nws;
    private static JsonNode nwsRaw;

    /** A Small Craft Advisory CON from the fixture, chosen because it carries every NWS addition. */
    private static final String SCA_ID =
            "urn:oid:2.49.0.1.840.0.d23f346b0ae1a954eba9c52762f3ddbe1b913873.001.1";

    @BeforeAll
    static void load() throws Exception {
        AlertIngestService ingest = new AlertIngestService(new NwsZoneService());
        try (InputStream in = AlertIngestSourceFieldsTest.class
                .getResourceAsStream("/fixtures/nws-active-2026-08-22.json")) {
            nwsRaw = MAPPER.readTree(in);
            nws = ingest.parseNwsFeed(nwsRaw);
        }
    }

    private static NormalizedAlert byId(String id) {
        return nws.stream().filter(a -> id.equals(a.id())).findFirst().orElseThrow();
    }

    // ── NWS ─────────────────────────────────────────────────────────────

    @Test
    void nwsKeepsTheOfficeNameAndTheFourRawTimings() {
        NormalizedAlert a = byId(SCA_ID);

        assertThat(a.senderName()).isEqualTo("NWS Grand Rapids MI");
        assertThat(a.effective()).isEqualTo("2026-08-22T14:59:00-04:00");
        assertThat(a.onset()).isEqualTo("2026-08-22T14:59:00-04:00");
        assertThat(a.expires()).isEqualTo("2026-08-22T23:00:00-04:00");
        assertThat(a.ends()).isEqualTo("2026-08-23T23:00:00-04:00");
        // The collapsed pair every existing consumer reads is unchanged.
        assertThat(a.startedAt()).isEqualTo(a.onset());
        assertThat(a.endsAt()).isEqualTo(a.ends());
    }

    @Test
    void nwsKeepsWholeReferenceTuples_andTheIdentifierViewStillMatches() {
        NormalizedAlert a = byId(SCA_ID);

        assertThat(a.referenceTuples()).isNotEmpty();
        CapReference first = a.referenceTuples().get(0);
        assertThat(first.identifier()).isEqualTo(
                "urn:oid:2.49.0.1.840.0.0bc3c7d47595d4cf0a8772cb926a605db072037e.001.1");
        assertThat(first.sender()).isEqualTo("w-nws.webmaster@noaa.gov");
        assertThat(first.sent()).isEqualTo("2026-08-22T09:55:00-04:00");
        assertThat(a.references())
                .containsExactlyElementsOf(a.referenceTuples().stream().map(CapReference::identifier).toList());
    }

    @Test
    void nwsParsesVtecIntoTheEventThread() {
        NormalizedAlert a = byId(SCA_ID);

        assertThat(a.vtec()).hasSize(1);
        Vtec v = a.vtec().get(0);
        assertThat(v.action()).isEqualTo("CON");
        assertThat(v.office()).isEqualTo("KGRR");
        assertThat(v.beginsAt()).as("000000T0000Z is 'already in effect'").isNull();
        assertThat(a.vtecEventKeys()).containsExactly("KGRR.SC.Y.0060.2026");
    }

    @Test
    void everyVtecInTheCapturedFeedParses() {
        // 277 of 310 alerts carry one, all operational — none may be dropped.
        long withRawVtec = nws.stream()
                .filter(a -> a.parameters() != null && a.parameters().containsKey("VTEC")).count();
        long withParsed = nws.stream().filter(a -> !a.vtec().isEmpty()).count();
        long withKey = nws.stream().filter(a -> !a.vtecEventKeys().isEmpty()).count();

        assertThat(withRawVtec).isGreaterThan(200);
        assertThat(withParsed).isEqualTo(withRawVtec);
        assertThat(withKey).isEqualTo(withRawVtec);
    }

    @Test
    void nwsRecordTypeIsLeftForTheTemplate_usgsAndFemaFixTheirOwn() {
        assertThat(byId(SCA_ID).recordType()).isNull();
        assertThat(TestAlerts.usgs("M6.1 — x").build().recordType()).isEqualTo("earthquake_information");
        assertThat(TestAlerts.fema("Flood — x").build().recordType()).isEqualTo("disaster_declaration");
    }

    // ── USGS ────────────────────────────────────────────────────────────

    private static final String QUAKE = """
            {"type":"Feature","id":"us7000tsu",
             "properties":{"mag":7.1,"place":"80 km S of Somewhere, AK","time":1760356800000,
               "updated":1760360400000,"alert":"orange","tsunami":1,"title":"M 7.1 - 80 km S of Somewhere, AK"},
             "geometry":{"type":"Point","coordinates":[-150.1,58.2,33.0]}}""";

    @Test
    void usgsKeepsMagnitudePagerTsunamiAndTheRevisionStamp() throws Exception {
        NormalizedAlert q = new AlertIngestService(new NwsZoneService()).normalizeUsgs(MAPPER.readTree(QUAKE));

        assertThat(q.magnitude()).isEqualTo(7.1);
        assertThat(q.pagerAlert()).isEqualTo("orange");
        assertThat(q.tsunamiFlag()).isTrue();
        assertThat(q.revisionUpdatedAt()).isEqualTo(Instant.ofEpochMilli(1760360400000L).toString());
        assertThat(q.recordType()).isEqualTo("earthquake_information");
        // Unchanged legacy fields.
        assertThat(q.startedAt()).isEqualTo(Instant.ofEpochMilli(1760356800000L).toString());
        assertThat(q.endsAt()).isNull();
    }

    @Test
    void usgsFieldsNotSentAreNull_notInvented() throws Exception {
        NormalizedAlert q = new AlertIngestService(new NwsZoneService()).normalizeUsgs(MAPPER.readTree("""
                {"type":"Feature","id":"us1","properties":{"place":"x","time":1760356800000,"alert":null},
                 "geometry":{"type":"Point","coordinates":[-1,1,1]}}"""));

        assertThat(q.magnitude()).isNull();
        assertThat(q.pagerAlert()).as("not assessed is not green").isNull();
        assertThat(q.tsunamiFlag()).as("absent is not a no").isNull();
        assertThat(q.revisionUpdatedAt()).isNull();

        NormalizedAlert zero = new AlertIngestService(new NwsZoneService()).normalizeUsgs(MAPPER.readTree("""
                {"type":"Feature","id":"us2","properties":{"mag":4.9,"tsunami":0,"time":1},
                 "geometry":{"type":"Point","coordinates":[-1,1,1]}}"""));
        assertThat(zero.tsunamiFlag()).isFalse();
    }

    // ── FEMA (R13) ──────────────────────────────────────────────────────

    private static NormalizedAlert pollOneFema(String row, Instant at) {
        AlertIngestService ingest = new AlertIngestService(new NwsZoneService());
        ingest.setUpstreamForTest((url, accept) -> url.contains("fema.gov")
                        ? MAPPER.readTree("{\"DisasterDeclarationsSummaries\":[" + row + "]}")
                        : MAPPER.readTree("{\"features\":[]}"),
                Clock.fixed(at, ZoneOffset.UTC));
        ingest.refreshNow();
        return ingest.getSnapshot().alerts().stream()
                .filter(a -> "FEMA".equals(a.source())).findFirst().orElseThrow();
    }

    private static final String FEMA_ROW = """
            {"femaDeclarationString":"DR-9999-OK","disasterNumber":9999,"state":"OK",
             "declarationType":"DR","declarationDate":"2026-09-01T00:00:00.000Z",
             "incidentBeginDate":"2026-08-20T00:00:00.000Z","incidentEndDate":"2026-08-25T00:00:00.000Z",
             "lastIAFilingDate":"2026-11-01T00:00:00.000Z",
             "incidentType":"Severe Storm","declarationTitle":"SEVERE STORMS",
             "fipsStateCode":"40","fipsCountyCode":"027",
             "designatedArea":"Cleveland (County)","ihProgramDeclared":true}""";

    private static final Instant FEMA_NOW = Instant.parse("2026-09-10T12:00:00Z");

    @Test
    void femaCarriesDeclarationAndIncidentDatesAsTheirOwnFields() {
        NormalizedAlert f = pollOneFema(FEMA_ROW, FEMA_NOW);

        assertThat(f.declaredAt()).isEqualTo("2026-09-01T00:00:00.000Z");
        assertThat(f.incidentEndsAt()).isEqualTo("2026-08-25T00:00:00.000Z");
        assertThat(f.startedAt()).isEqualTo("2026-08-20T00:00:00.000Z");
        assertThat(f.recordType()).isEqualTo("disaster_declaration");
    }

    /**
     * R13: a declaration's {@code endsAt} is the end of its recovery window,
     * not the end of the incident. Every IA declaration's incident is over by
     * the time help is offered, so with the incident end here the lifecycle
     * check read every active-recovery declaration as expired.
     */
    @Test
    void femaEndsAtIsTheRecoveryWindow_notTheIncidentEnd() {
        NormalizedAlert f = pollOneFema(FEMA_ROW, FEMA_NOW);
        assertThat(f.endsAt()).isEqualTo("2026-11-01T00:00:00.000Z");
        assertThat(AlertSafetyPolicy.lifecycleBlockReason(f)).isNull();

        NormalizedAlert open = pollOneFema(FEMA_ROW.replace(
                "\"incidentEndDate\":\"2026-08-25T00:00:00.000Z\",", ""), FEMA_NOW);
        assertThat(open.incidentEndsAt()).isNull();
        assertThat(open.endsAt()).isEqualTo("2026-11-01T00:00:00.000Z");
    }

    @Test
    void femaWithNoFilingDeadlineHasNoEnd_notTheIncidentEnd() {
        NormalizedAlert f = pollOneFema(FEMA_ROW.replace(
                "\"lastIAFilingDate\":\"2026-11-01T00:00:00.000Z\",", ""), FEMA_NOW);
        assertThat(f.endsAt()).isNull();
        assertThat(f.incidentEndsAt()).isEqualTo("2026-08-25T00:00:00.000Z");
    }

    /**
     * FEMA rows DO carry codes: {@code fipsStateCode} + {@code fipsCountyCode}
     * per designated area, which is the SAME location code with a whole-county
     * prefix. A reservation row is coded {@code 000} with no county, so it
     * contributes no code rather than reading as the whole state; only FEMA's
     * own {@code Statewide} designation does.
     */
    @Test
    void femaDesignatedCountiesBecomeSameCodes() {
        AlertIngestService ingest = new AlertIngestService(new NwsZoneService());
        String reservation = FEMA_ROW.replace("\"027\"", "\"000\"")
                .replace("Cleveland (County)", "Kiowa Indian Reservation");
        String second = FEMA_ROW.replace("\"027\"", "\"109\"").replace("Cleveland", "Oklahoma");
        ingest.setUpstreamForTest((url, accept) -> url.contains("fema.gov")
                        ? MAPPER.readTree("{\"DisasterDeclarationsSummaries\":["
                                + FEMA_ROW + "," + reservation + "," + second + "]}")
                        : MAPPER.readTree("{\"features\":[]}"),
                Clock.fixed(FEMA_NOW, ZoneOffset.UTC));
        ingest.refreshNow();
        NormalizedAlert f = ingest.getSnapshot().alerts().stream()
                .filter(a -> "FEMA".equals(a.source())).findFirst().orElseThrow();

        assertThat(f.same()).containsExactly("040027", "040109");
        assertThat(AlertIngestService.targetCodes(f)).containsExactly("OKC027", "OKC109");
        assertThat(f.ugc()).as("UGC stays empty: dispatch and member areas read it").isEmpty();

        NormalizedAlert statewide = pollOneFema(FEMA_ROW.replace("\"027\"", "\"000\"")
                .replace("Cleveland (County)", "Statewide"), FEMA_NOW);
        assertThat(statewide.same()).containsExactly("040000");

        NormalizedAlert reservationOnly = pollOneFema(reservation, FEMA_NOW);
        assertThat(reservationOnly.same()).isEmpty();
    }

    /**
     * The captured 2026-08-22 OpenFEMA page, through the real poll: what R13
     * plus county scoping does to the feed's inputs. Every active-recovery
     * declaration is now current (none was before), and each is placed by its
     * own counties — except the reservation-only ones, which are placed nowhere.
     */
    private static final Instant CAPTURED = Instant.parse("2026-08-22T12:00:00Z");

    @Test
    void theCapturedFemaPageIsCurrentAndCountyScoped() throws Exception {
        JsonNode page;
        try (InputStream in = AlertIngestSourceFieldsTest.class
                .getResourceAsStream("/fixtures/fema-declarations-2026-08-22.json")) {
            page = MAPPER.readTree(in);
        }
        AlertIngestService ingest = new AlertIngestService(new NwsZoneService());
        ingest.setUpstreamForTest((url, accept) -> url.contains("fema.gov")
                        ? (url.contains("skip=0") ? page : MAPPER.readTree("{\"DisasterDeclarationsSummaries\":[]}"))
                        : MAPPER.readTree("{\"features\":[]}"),
                Clock.fixed(CAPTURED, ZoneOffset.UTC));
        ingest.refreshNow();
        List<NormalizedAlert> fema = ingest.getSnapshot().alerts().stream()
                .filter(a -> "FEMA".equals(a.source())).toList();

        // Judged on the day the page was captured: these windows have since closed.
        assertThat(fema).hasSize(9);
        assertThat(fema).allSatisfy(f -> {
            assertThat(f.endsAt()).as(f.id()).isNotNull();
            assertThat(Instant.parse(f.endsAt())).as(f.id()).isAfter(CAPTURED);
            assertThat(AlertDerivations.lifecycleState(f, CAPTURED)).as(f.id()).isEqualTo("active");
            // Under the incident end, every one of them read as over.
            assertThat(Instant.parse(f.incidentEndsAt())).as(f.id()).isBefore(CAPTURED);
        });
        NormalizedAlert wv = fema.stream().filter(f -> "DR-4932-WV".equals(f.id())).findFirst().orElseThrow();
        assertThat(AlertIngestService.targetCodes(wv)).contains("WVC085", "WVC073", "WVC041");
        // Salt Lake City (UTZ104 / UTC035) is in none of them.
        Set<String> slc = Set.of("UTZ104", "UTC035");
        assertThat(fema).allSatisfy(f -> assertThat(AlertIngestService.matchTypeFor(
                f, 40.76, -111.89, 80, slc, AlertIngestService.statePrefixesOf(slc))).as(f.id()).isNull());
        // A Ritchie County, WV viewer sees the WV declaration by zone.
        Set<String> ritchie = Set.of("WVZ011", "WVC085");
        assertThat(AlertIngestService.matchTypeFor(wv, 39.18, -81.06, 80, ritchie,
                AlertIngestService.statePrefixesOf(ritchie))).isEqualTo(AlertIngestService.MatchType.ZONE);
    }

    /**
     * A FEMA row with no code at all is unplaced, not everywhere. It used to
     * match every coordinate as BROADCAST, which is how a declaration in one
     * state read as local news in all the others.
     */
    @Test
    void aFemaRowWithNoCodesMatchesNoOne_neverBroadcast() {
        NormalizedAlert bare = TestAlerts.fema("Severe Storm — X").same(List.of()).ugc(List.of()).build();
        Set<String> slc = Set.of("UTZ104", "UTC035");
        assertThat(AlertIngestService.matchTypeFor(bare, 40.76, -111.89, 80, slc,
                AlertIngestService.statePrefixesOf(slc))).isNull();
        assertThat(AlertIngestService.matchTypeFor(bare, 40.76, -111.89, 80, Set.of(), Set.of())).isNull();
    }

    // ── persistence compatibility ───────────────────────────────────────

    /**
     * {@code AlertHistoryService} round-trips the record through a plain
     * ObjectMapper (no java.time module). Every addition must survive that, and
     * a payload written before them must still rehydrate.
     */
    @Test
    void theRecordStillRoundTripsThroughHistorysPlainObjectMapper() throws Exception {
        ObjectMapper plain = new ObjectMapper();
        NormalizedAlert a = byId(SCA_ID);

        NormalizedAlert back = plain.readValue(plain.writeValueAsString(a), NormalizedAlert.class);

        assertThat(back.vtec()).isEqualTo(a.vtec());
        assertThat(back.vtecEventKeys()).isEqualTo(a.vtecEventKeys());
        assertThat(back.referenceTuples()).isEqualTo(a.referenceTuples());
        assertThat(back.senderName()).isEqualTo(a.senderName());
        assertThat(back.ends()).isEqualTo(a.ends());
        assertThat(back).isEqualTo(a);
    }

    @Test
    void aPreS2HistoryPayloadRehydratesWithEmptiesNotNullLists() throws Exception {
        ObjectMapper plain = new ObjectMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> asMap = plain.convertValue(TestAlerts.fema("Flood — x").build(), Map.class);
        for (String added : List.of("senderName", "effective", "onset", "expires", "ends",
                "referenceTuples", "vtec", "vtecEventKeys", "revisionUpdatedAt", "magnitude",
                "pagerAlert", "tsunamiFlag", "incidentEndsAt", "declaredAt", "recordType")) {
            assertThat(asMap).as("serialised").containsKey(added);
            asMap.remove(added);
        }

        NormalizedAlert old = plain.readValue(plain.writeValueAsString(asMap), NormalizedAlert.class);

        assertThat(old.referenceTuples()).isEmpty();
        assertThat(old.vtec()).isEmpty();
        assertThat(old.vtecEventKeys()).isEmpty();
        assertThat(old.declaredAt()).isNull();
        assertThat(old.recordType()).as("derived from the source when absent").isEqualTo("disaster_declaration");
    }
}
