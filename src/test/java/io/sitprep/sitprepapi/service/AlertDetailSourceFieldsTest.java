package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.dto.AlertCardDto;
import io.sitprep.sitprepapi.service.AlertIngestService.MatchType;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The feed's {@code detail} block reads what the source sent (Phase 3 S2 C1).
 *
 * <p>{@code tsunami} was a hard-coded {@code false} and {@code pagerLevel} a
 * hard-coded null, because the USGS normalizer dropped both; the FEMA
 * {@code declaredAt} was the incident's BEGIN date, which is not when anything
 * was declared. Each is now the upstream field, verbatim, or null.</p>
 */
class AlertDetailSourceFieldsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static AlertIngestService ingest;
    private static AlertFeedService feed;

    @BeforeAll
    static void setUp() {
        NwsZoneService zones = new NwsZoneService();
        ingest = new AlertIngestService(zones);
        AlertDispatchService dispatch = new AlertDispatchService(null, null, null, null, null, null, zones, null);
        dispatch.loadTemplates();
        feed = new AlertFeedService(ingest, dispatch);
    }

    @Test
    void usgsDetailCarriesTheWireTsunamiFlagAndPagerLevel() throws Exception {
        NormalizedAlert quake = ingest.normalizeUsgs(MAPPER.readTree("""
                {"type":"Feature","id":"us7000tsu",
                 "properties":{"mag":7.1,"place":"80 km S of Somewhere, AK","time":1760356800000,
                   "updated":1760360400000,"alert":"orange","tsunami":1,"title":"M 7.1 - 80 km S of Somewhere, AK"},
                 "geometry":{"type":"Point","coordinates":[-150.1,58.2,33.0]}}"""));

        AlertCardDto.Earthquake eq = feed.toCard(quake, MatchType.POLYGON, 50).detail().earthquake();

        assertThat(eq.tsunami()).isTrue();
        assertThat(eq.pagerLevel()).isEqualTo("orange");
        assertThat(eq.magnitude()).isEqualTo(7.1);
        assertThat(eq.depthKm()).isEqualTo(33.0);
    }

    @Test
    void femaDetailDeclaredAtIsTheDeclarationDate_notTheIncidentBegin() {
        ingest.setUpstreamForTest((url, accept) -> MAPPER.readTree("""
                {"DisasterDeclarationsSummaries":[{
                  "femaDeclarationString":"DR-9999-OK","disasterNumber":9999,"state":"OK",
                  "declarationType":"DR","declarationDate":"2026-09-01T00:00:00.000Z",
                  "incidentBeginDate":"2026-08-20T00:00:00.000Z","incidentEndDate":"2026-08-25T00:00:00.000Z",
                  "lastIAFilingDate":"2026-11-01T00:00:00.000Z",
                  "incidentType":"Severe Storm","declarationTitle":"SEVERE STORMS",
                  "designatedArea":"Cleveland (County)","ihProgramDeclared":true}]}"""),
                Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC));
        ingest.refreshNow();
        NormalizedAlert fema = ingest.getSnapshot().alerts().stream()
                .filter(a -> "FEMA".equals(a.source())).findFirst().orElseThrow();

        AlertCardDto.Disaster d = feed.toCard(fema, MatchType.BROADCAST, 50).detail().disaster();

        assertThat(d.declaredAt()).isEqualTo("2026-09-01T00:00:00.000Z");
    }

    /**
     * R13 surfaces active-recovery declarations on the feed, so this pins WHERE
     * and HOW: only a viewer in a declared county, by zone (region confidence,
     * never containment), and never as life-safety. A Salt Lake City viewer and
     * an Oklahoma declaration over Cleveland County (OKC027):
     */
    @Test
    void aCurrentFemaDeclarationReachesOnlyItsCounties_andNeverAsLifeSafety() {
        NwsZoneService zones = new NwsZoneService();
        zones.seedPointZones(40.76, -111.89, Set.of("UTZ104", "UTC035"));
        zones.seedPointZones(35.22, -97.44, Set.of("OKZ025", "OKC027"));
        AlertIngestService local = new AlertIngestService(zones);
        AlertDispatchService dispatch = new AlertDispatchService(null, null, null, null, null, null, zones, null);
        dispatch.loadTemplates();

        NormalizedAlert declared = TestAlerts.fema("Severe Storm — SEVERE STORMS").id("DR-9999-OK")
                .severity("Minor").startedAt("2026-08-20T00:00:00Z")
                .endsAt("2099-11-01T00:00:00Z").incidentEndsAt("2026-08-25T00:00:00Z")
                .same(List.of("040027")).build();
        // A reservation-only declaration: no county code, so placed nowhere.
        NormalizedAlert unplaced = TestAlerts.fema("Severe Storm — SEVERE STORMS").id("DR-9998-MT")
                .severity("Minor").startedAt("2026-08-20T00:00:00Z")
                .endsAt("2099-11-01T00:00:00Z").same(List.of()).build();
        local.setSnapshotForTest(List.of(declared, unplaced));
        AlertFeedService localFeed = new AlertFeedService(local, dispatch);

        assertThat(localFeed.feedFor(40.76, -111.89).alerts()).as("Utah sees no Oklahoma declaration").isEmpty();

        List<AlertCardDto> ok = localFeed.feedFor(35.22, -97.44).alerts();
        assertThat(ok).extracting(AlertCardDto::id).containsExactly("DR-9999-OK");
        AlertCardDto card = ok.get(0);
        assertThat(card.location().matchType()).isEqualTo("zone");
        assertThat(card.location().confidence()).isEqualTo("region");
        assertThat(card.lifecycleState()).isEqualTo("active");
        assertThat(card.expiresAt()).isEqualTo("2099-11-01T00:00:00Z");
        // Never life-safety: FEMA templates are watch-tier, feed-only.
        assertThat(card.tier()).isEqualTo("watch");
        assertThat(card.isLifeThreatening()).isFalse();
        assertThat(card.safety().dispatchMode()).isEqualTo("feed");
        assertThat(card.safety().movementDirective()).isEqualTo("none");
        // FEMA sends no CAP response, so the only action is "unknown" — never a
        // movement or protective one.
        assertThat(card.safety().protectiveActions()).containsExactly("UNKNOWN");

        // Dispatch places posts (and centres pushes) from geometry and UGC only:
        // a FEMA row has neither, so it is never posted or pushed.
        assertThat(dispatch.dispatchCoordCandidates(declared)).isEmpty();
        // Member areas also read UGC only: nobody is ever "inside" a declaration.
        MemberAlertAreaService areas = new MemberAlertAreaService(local, dispatch, zones);
        assertThat(areas.activeAreas()).extracting(a -> a.alert().id()).contains("DR-9999-OK");
        assertThat(MemberAlertAreaService.match(areas.activeAreas(), 35.22, -97.44,
                Set.of("OKZ025", "OKC027"), Instant.now())).isEmpty();
    }
}
