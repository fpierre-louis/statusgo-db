package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.sitprep.sitprepapi.dto.AlertCardDto;
import io.sitprep.sitprepapi.service.AlertIngestService.CapReference;
import io.sitprep.sitprepapi.service.AlertIngestService.MatchType;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.util.Vtec;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2 C2 — the card's LEGACY fields, pinned to what {@code alerts/p3-C1} served.
 *
 * <p>The contract (§3) is additive: every field a card carried before C2 keeps
 * its value. Those fields feed more than the screen — {@code lifecycleState}
 * is what {@code ConcealmentSafetyService} and {@code ActivationDirectiveResolver}
 * filter "still live" on, and {@code safety} carries the directive — so a
 * changed value is a changed safety input, not a cosmetic diff.</p>
 *
 * <p>The golden file was captured by running THIS class on {@code alerts/p3-C1}
 * with {@code GOLDEN_WRITE=<path>}; it is not regenerated from the code under
 * test. The fixtures are the cases where the interpreter has its own,
 * more specific lifecycle opinion (own and replacing VTEC, CAP-only edges,
 * expiry) plus one of each source, so a reader cannot quietly start following
 * that opinion.</p>
 */
class AlertCardLegacyGoldenTest {

    private static final String GOLDEN = "/fixtures/alert-card-legacy-golden-c1.json";
    private static final String FUTURE = "2099-01-01T00:00:00Z";
    private static final String PAST = "2020-01-01T00:00:00Z";
    private static final Set<String> SAN_CARLOS = Set.of("AZZ560", "AZC007", "AZZ133");
    private static final double LAT = 33.3172, LNG = -110.5297;

    /** Every field AlertCardDto carried on alerts/p3-C1. */
    static final List<String> LEGACY_FIELDS = List.of(
            "id", "source", "eventType", "eventLabel", "tier", "isLifeThreatening",
            "evacuationRelated", "headline", "whatToDo", "precautions", "precautionsSource",
            "lifecycleState", "replacesAlertIds", "supersededBy", "official", "location", "radiusMi",
            "effectiveAt", "expiresAt", "sourceAttribution", "detail", "safety");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private AlertFeedService feed;

    @BeforeEach
    void setUp() {
        NwsZoneService zones = new NwsZoneService();
        zones.seedPointZones(LAT, LNG, SAN_CARLOS);
        AlertIngestService ingest = new AlertIngestService(zones);
        AlertDispatchService dispatch = new AlertDispatchService(null, null, null, null, null, null, zones, null);
        dispatch.loadTemplates();
        feed = new AlertFeedService(ingest, dispatch);
    }

    private static Vtec vtec(String action, String ph, String sig, String etn) {
        return new Vtec(action, "KPSR", ph, sig, etn, null, null, 2026);
    }

    private static TestAlerts.Builder tornado(String id) {
        return TestAlerts.nws("Tornado Warning").id(id).endsAt(FUTURE)
                .sent("2026-10-13T12:00:00Z").instruction("Take cover now in a basement or interior room.");
    }

    private static NormalizedAlert original() {
        return tornado("urn:oid:A").messageType("Alert").vtec(List.of(vtec("NEW", "TO", "W", "0042"))).build();
    }

    private static TestAlerts.Builder replacing(String action) {
        return tornado("urn:oid:B").messageType("Update").sent("2026-10-13T17:00:00Z")
                .referenceTuples(List.of(new CapReference("w-nws.webmaster@noaa.gov", "urn:oid:A", "2026-10-13T12:00:00Z")))
                .vtec(List.of(vtec(action, "TO", "W", "0042")));
    }

    /** Fixture name → the card it yields, in a stable order. */
    private Map<String, Supplier<AlertCardDto>> fixtures() {
        Map<String, Supplier<AlertCardDto>> f = new LinkedHashMap<>();
        f.put("live_flood_warning", () -> feed.toCard(TestAlerts.nws("Flood Warning").id("urn:oid:FW").endsAt(FUTURE)
                .instruction("Turn around, don't drown.").build(), MatchType.ZONE, 50));
        f.put("watch", () -> feed.toCard(TestAlerts.nwsWatch("Flood Watch").id("urn:oid:WA").endsAt(FUTURE).build(),
                MatchType.ZONE, 50));
        f.put("severe_watch", () -> feed.toCard(TestAlerts.nwsWatch("Tornado Watch").id("urn:oid:TA").severity("Extreme")
                .endsAt(FUTURE).build(), MatchType.ZONE, 50));
        f.put("official_only", () -> feed.toCard(TestAlerts.nws("Civil Danger Warning").id("urn:oid:CD").endsAt(FUTURE)
                .instruction("Shelter in place now.").build(), MatchType.ZONE, 50));
        for (String act : List.of("CON", "EXT", "EXP", "CAN")) {
            f.put("own_vtec_" + act.toLowerCase() + "_update", () -> feed.toCard(
                    tornado("urn:oid:O" + act).messageType("Update").vtec(List.of(vtec(act, "TO", "W", "0042"))).build(),
                    MatchType.ZONE, 50));
        }
        f.put("own_vtec_con_alert", () -> feed.toCard(
                tornado("urn:oid:OCA").messageType("Alert").vtec(List.of(vtec("CON", "TO", "W", "0042"))).build(),
                MatchType.ZONE, 50));
        for (String act : List.of("CAN", "EXP", "UPG", "CON")) {
            f.put("replaced_by_vtec_" + act.toLowerCase(), () -> feed.toCard(original(), MatchType.ZONE, 50,
                    replacing(act).build()));
        }
        f.put("replaced_by_vtec_exp_cancel_msg", () -> feed.toCard(original(), MatchType.ZONE, 50,
                replacing("EXP").messageType("Cancel").build()));
        f.put("replaced_by_cap_cancel", () -> feed.toCard(original(), MatchType.ZONE, 50,
                tornado("urn:oid:C").messageType("Cancel").references(List.of("urn:oid:A")).build()));
        f.put("replaced_by_allclear", () -> feed.toCard(original(), MatchType.ZONE, 50,
                tornado("urn:oid:D").messageType("Update").response("AllClear").references(List.of("urn:oid:A")).build()));
        f.put("expired_evacuation", () -> feed.toCard(TestAlerts.nws("Evacuation Immediate").id("urn:oid:EV")
                .endsAt(PAST).responseTypes(List.of("Evacuate")).instruction("Leave now via Route 9.").build(),
                MatchType.ZONE, 50));
        f.put("usgs", () -> feed.toCard(TestAlerts.usgs("M 6.1 - 10 km N of Somewhere").id("us7000abc").magnitude(6.1)
                .geometry(Map.of("type", "Point", "coordinates", List.of(LNG, LAT, 10.0))).build(),
                MatchType.POLYGON, 50));
        f.put("fema", () -> feed.toCard(TestAlerts.fema("Severe Storms (DR-4932-WV)").id("DR-4932-WV").build(),
                MatchType.BROADCAST, 50));
        for (String state : List.of("active", "updated", "expired", "cancelled", "superseded", "no_longer_listed")) {
            f.put("record_" + state, () -> feed.toRecordCard(tornado("urn:oid:R").build(), MatchType.ZONE, 50, state));
        }
        f.put("record_own_vtec_can_no_longer_listed", () -> feed.toRecordCard(
                tornado("urn:oid:RC").messageType("Update").vtec(List.of(vtec("CAN", "TO", "W", "0042"))).build(),
                MatchType.ZONE, 50, "no_longer_listed"));
        return f;
    }

    private ObjectNode legacyView() {
        ObjectNode out = MAPPER.createObjectNode();
        fixtures().forEach((name, card) -> {
            JsonNode full = MAPPER.valueToTree(card.get());
            ObjectNode legacy = out.putObject(name);
            for (String field : LEGACY_FIELDS) {
                JsonNode v = full.get(field);
                if (v != null) legacy.set(field, v);
            }
        });
        return out;
    }

    @Test
    void legacyFieldsMatchC1ByteForByte() throws Exception {
        ObjectNode now = legacyView();
        String write = System.getenv("GOLDEN_WRITE");
        if (write != null && !write.isBlank()) {
            Files.writeString(Path.of(write), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(now));
            return;
        }
        JsonNode golden;
        try (InputStream in = AlertCardLegacyGoldenTest.class.getResourceAsStream(GOLDEN)) {
            assertThat(in).as("golden file " + GOLDEN).isNotNull();
            golden = MAPPER.readTree(in);
        }
        assertThat(now.size()).isEqualTo(golden.size());
        // Every mismatch at once: a partial list hides which fixtures moved.
        SoftAssertions soft = new SoftAssertions();
        golden.fieldNames().forEachRemaining(name -> {
            JsonNode was = golden.get(name);
            JsonNode is = now.get(name);
            soft.assertThat(is).as(name).isNotNull();
            if (is == null) return;
            for (String field : LEGACY_FIELDS) {
                soft.assertThat(is.get(field)).as(name + "." + field).isEqualTo(was.get(field));
            }
        });
        soft.assertAll();
    }
}
