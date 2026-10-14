package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.service.AlertIngestService.CapReference;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.util.AlertSourceFingerprint;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Source fingerprint and revision ref (S2 contract §1).
 *
 * <p>The two properties everything downstream relies on: polling the same
 * payload again yields the same ref (no new revision, no new notification), and
 * an issuer's change under the same id — the USGS case — yields a new one.</p>
 */
class AlertSourceFingerprintTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String quake(double mag, String alert, int tsunami, long updated) {
        return """
                {"type":"Feature","id":"us7000abc",
                 "properties":{"mag":%s,"place":"10 km N of Somewhere, CA","time":1760356800000,
                   "updated":%d,"alert":%s,"tsunami":%d,"title":"M - 10 km N of Somewhere, CA"},
                 "geometry":{"type":"Point","coordinates":[-118.2,34.0,10.0]}}"""
                .formatted(mag, updated, alert == null ? "null" : "\"" + alert + "\"", tsunami);
    }

    private static NormalizedAlert usgs(String body) throws Exception {
        return new AlertIngestService(new NwsZoneService()).normalizeUsgs(MAPPER.readTree(body));
    }

    @Test
    void theRefIsDedupeKeyAtTwelveHexChars() throws Exception {
        NormalizedAlert q = usgs(quake(5.9, null, 0, 1760356900000L));
        String fp = AlertSourceFingerprint.fingerprint(q);

        assertThat(fp).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(AlertSourceFingerprint.revisionRef(q)).isEqualTo("USGS:us7000abc@" + fp.substring(0, 12));
        assertThat(AlertSourceFingerprint.revisionRef("USGS:us7000abc", fp))
                .isEqualTo(AlertSourceFingerprint.revisionRef(q));
    }

    @Test
    void theSamePayloadPolledTwiceYieldsTheSameRef() throws Exception {
        String body = quake(5.9, "green", 0, 1760356900000L);
        assertThat(AlertSourceFingerprint.revisionRef(usgs(body)))
                .isEqualTo(AlertSourceFingerprint.revisionRef(usgs(body)));
    }

    @Test
    void aUsgsMagnitudeRevisionUnderTheSameIdYieldsANewRef() throws Exception {
        NormalizedAlert first = usgs(quake(5.9, null, 0, 1760356900000L));
        NormalizedAlert revised = usgs(quake(6.2, null, 0, 1760357900000L));

        assertThat(revised.id()).isEqualTo(first.id());
        assertThat(AlertSourceFingerprint.revisionRef(revised))
                .startsWith("USGS:us7000abc@")
                .isNotEqualTo(AlertSourceFingerprint.revisionRef(first));
    }

    @Test
    void pagerAndTsunamiChangesAreRevisionsToo() throws Exception {
        String base = AlertSourceFingerprint.revisionRef(usgs(quake(6.2, null, 0, 1L)));
        assertThat(AlertSourceFingerprint.revisionRef(usgs(quake(6.2, "yellow", 0, 1L)))).isNotEqualTo(base);
        assertThat(AlertSourceFingerprint.revisionRef(usgs(quake(6.2, null, 1, 1L)))).isNotEqualTo(base);
    }

    @Test
    void aReStampWithNothingChangedIsNotARevision() throws Exception {
        // USGS `updated` moves on reviews that change nothing a reader sees.
        assertThat(AlertSourceFingerprint.revisionRef(usgs(quake(6.2, null, 0, 1L))))
                .isEqualTo(AlertSourceFingerprint.revisionRef(usgs(quake(6.2, null, 0, 2L))));
    }

    @Test
    void listOrderAndGeometryKeyOrderDoNotChangeTheHash() {
        Map<String, Object> geomA = new LinkedHashMap<>();
        geomA.put("type", "Polygon");
        geomA.put("coordinates", List.of(List.of(List.of(-97, 35), List.of(-96, 35), List.of(-97, 35))));
        Map<String, Object> geomB = new LinkedHashMap<>();
        geomB.put("coordinates", geomA.get("coordinates"));
        geomB.put("type", "Polygon");

        NormalizedAlert a = TestAlerts.nws("Flood Warning").geometry(geomA)
                .ugc(List.of("UTZ104", "UTZ105")).same(List.of("049035", "049049"))
                .responseTypes(List.of("Avoid", "Monitor")).build();
        NormalizedAlert b = TestAlerts.nws("Flood Warning").geometry(geomB)
                .ugc(List.of("UTZ105", "UTZ104")).same(List.of("049049", "049035"))
                .responseTypes(List.of("Monitor", "Avoid")).build();

        assertThat(AlertSourceFingerprint.fingerprint(a)).isEqualTo(AlertSourceFingerprint.fingerprint(b));
    }

    @Test
    void eachContractFieldMovesTheHash() {
        NormalizedAlert base = TestAlerts.nws("Flood Warning").build();
        String fp = AlertSourceFingerprint.fingerprint(base);

        List<NormalizedAlert> changed = List.of(
                TestAlerts.nws("Flood Warning").messageType("Update").build(),
                TestAlerts.nws("Flood Warning").severity("Extreme").build(),
                TestAlerts.nws("Flood Warning").instruction("Move to higher ground.").build(),
                TestAlerts.nws("Flood Warning").area("Elsewhere").build(),
                TestAlerts.nws("Flood Warning").ugc(List.of("AZZ561")).build(),
                TestAlerts.nws("Flood Warning").ends("2026-10-14T00:00:00Z").build(),
                TestAlerts.nws("Flood Warning").expires("2026-10-14T00:00:00Z").build(),
                TestAlerts.nws("Flood Warning").referenceTuples(
                        List.of(new CapReference("w-nws.webmaster@noaa.gov", "urn:oid:x", "2026-10-13T00:00:00Z"))).build(),
                TestAlerts.fema("Flood — x").build(),
                TestAlerts.nws("Flood Warning").declaredAt("2026-09-01").build());
        for (NormalizedAlert c : changed) {
            assertThat(AlertSourceFingerprint.fingerprint(c)).isNotEqualTo(fp);
        }
    }

    @Test
    void sentAloneDoesNotMoveTheHash() {
        assertThat(AlertSourceFingerprint.fingerprint(TestAlerts.nws("Flood Warning").sent("a").build()))
                .isEqualTo(AlertSourceFingerprint.fingerprint(TestAlerts.nws("Flood Warning").sent("b").build()));
    }

    @Test
    void separatorsInsideAValueCannotForgeANeighbour() {
        NormalizedAlert a = TestAlerts.nws("Flood Warning").headline("x\ndescription=0:").description("").build();
        NormalizedAlert b = TestAlerts.nws("Flood Warning").headline("x").description("\ndescription=0:").build();
        assertThat(AlertSourceFingerprint.fingerprint(a)).isNotEqualTo(AlertSourceFingerprint.fingerprint(b));
    }

    @Test
    void noRefWithoutAKnownSourceOrId() {
        assertThat(AlertSourceFingerprint.revisionRef(TestAlerts.nws("Flood Warning").id(null).build())).isNull();
        assertThat(AlertSourceFingerprint.revisionRef(TestAlerts.nws("Flood Warning").source("NOAA").build())).isNull();
        assertThat(AlertSourceFingerprint.revisionRef(null, "abc")).isNull();
        assertThat(AlertSourceFingerprint.fingerprint(null)).isNull();
    }
}
