package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.dto.AlertCardDto;
import io.sitprep.sitprepapi.resource.AppConfigResource;
import io.sitprep.sitprepapi.service.AlertIngestService.MatchType;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Phase 3 safety batch (U2): a card that is no longer in force is a RECORD.
 *
 * <p>Three properties, each a way the live feed used to present an ended
 * instruction as current:</p>
 * <ul>
 *   <li>the policy's as-issued directive reaches the wire, so a surface can say
 *       "an evacuation order was issued" without the card claiming one is in
 *       force (D2-card);</li>
 *   <li>an alert whose replacement is in the same snapshot is {@code superseded}
 *       and carries no SitPrep guidance and no current directive (D3);</li>
 *   <li>the template and the policy decision are computed once per alert, so the
 *       filter, the card and the life-threatening flag cannot disagree (D4).</li>
 * </ul>
 */
class AlertCardRecordTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** San Carlos AZ — TestAlerts' default zone AZZ560 is one of these. */
    private static final Set<String> SAN_CARLOS = Set.of("AZZ560", "AZC007", "AZZ133");
    private static final double LAT = 33.3172, LNG = -110.5297;

    /** Far enough out that the policy's own clock cannot call it expired. */
    private static final String FUTURE = "2099-01-01T00:00:00Z";
    private static final String PAST = "2020-01-01T00:00:00Z";

    private AlertIngestService ingest;
    private AlertDispatchService dispatch;
    private AlertFeedService feed;

    @BeforeEach
    void setUp() {
        NwsZoneService zones = new NwsZoneService();
        zones.seedPointZones(LAT, LNG, SAN_CARLOS);
        ingest = new AlertIngestService(zones);
        dispatch = spy(new AlertDispatchService(null, null, null, null, null, null, zones, null));
        dispatch.loadTemplates();
        feed = new AlertFeedService(ingest, dispatch);
    }

    private static NormalizedAlert evacuation(String id) {
        return TestAlerts.nws("Flood Warning")
                .id(id)
                .endsAt(FUTURE)
                .responseTypes(List.of("Evacuate"))
                .build();
    }

    private static NormalizedAlert guided(String id) {
        // Plain Flood Warning: covered by an approved template whose guidance
        // the policy allows. The control for "guidance is stripped".
        return TestAlerts.nws("Flood Warning").id(id).endsAt(FUTURE).build();
    }

    private static JsonNode json(AlertCardDto card) {
        return MAPPER.valueToTree(card);
    }

    private AlertCardDto cardFor(List<AlertCardDto> cards, String id) {
        return cards.stream().filter(c -> id.equals(c.id())).findFirst().orElseThrow(
                () -> new AssertionError("no card for " + id + " in " + cards.stream().map(AlertCardDto::id).toList()));
    }

    // ------------------------------------------------------------------
    // (a) the as-issued directive is on the wire
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a live order carries its directive both as current and as issued")
    void liveCardCarriesAsIssuedDirective() {
        AlertCardDto card = feed.toCard(evacuation("live-evac"), MatchType.ZONE, AppConfigResource.alertsRadiusMi());
        JsonNode safety = json(card).path("safety");
        assertThat(safety.path("movementDirective").asText()).isEqualTo("evacuate");
        assertThat(safety.path("asIssuedMovementDirective").asText()).isEqualTo("evacuate");
    }

    @Test
    @DisplayName("an expired order is not current, but the record of it survives")
    void expiredCardKeepsOnlyTheAsIssuedDirective() {
        NormalizedAlert expired = TestAlerts.nws("Flood Warning")
                .id("old-evac").endsAt(PAST).responseTypes(List.of("Evacuate")).build();
        JsonNode safety = json(feed.toCard(expired, MatchType.ZONE, AppConfigResource.alertsRadiusMi()))
                .path("safety");
        assertThat(safety.path("movementDirective").asText()).isEqualTo("none");
        assertThat(safety.path("asIssuedMovementDirective").asText()).isEqualTo("evacuate");
    }

    // ------------------------------------------------------------------
    // (b) a replaced alert in the same snapshot is a record
    // ------------------------------------------------------------------

    @Test
    @DisplayName("replaced in the same snapshot: superseded, no guidance, no current directive")
    void supersededCardIsARecord() {
        NormalizedAlert original = evacuation("urn:oid:OLD");
        NormalizedAlert update = TestAlerts.nws("Flood Warning")
                .id("urn:oid:NEW")
                .messageType("Update")
                .references(List.of("urn:oid:OLD"))
                .endsAt(FUTURE)
                .responseTypes(List.of("Evacuate"))
                .build();
        ingest.setSnapshotForTest(List.of(original, update));

        List<AlertCardDto> cards = feed.feedFor(LAT, LNG).alerts();
        AlertCardDto old = cardFor(cards, "urn:oid:OLD");
        AlertCardDto replacement = cardFor(cards, "urn:oid:NEW");

        assertThat(old.supersededBy()).isNotNull();
        assertThat(old.lifecycleState()).isEqualTo("superseded");
        assertThat(old.headline()).isNull();
        assertThat(old.whatToDo()).isNull();
        assertThat(old.precautions()).isNull();
        assertThat(old.precautionsSource()).isNull();
        assertThat(old.isLifeThreatening()).isFalse();
        // Still named — the record needs a title.
        assertThat(old.eventLabel()).isEqualTo("Flood Warning");

        JsonNode safety = json(old).path("safety");
        assertThat(safety.path("movementDirective").asText()).isEqualTo("none");
        assertThat(safety.path("asIssuedMovementDirective").asText()).isEqualTo("evacuate");
        assertThat(safety.path("protectiveActions").size()).isZero();
        assertThat(safety.path("guidanceMode").asText())
                .as("a record must not tell a client SitPrep guidance applies")
                .isNotEqualTo("supplement_official");

        // The replacement is what is in force, and keeps the order.
        assertThat(replacement.lifecycleState()).isEqualTo("updated");
        assertThat(json(replacement).path("safety").path("movementDirective").asText())
                .isEqualTo("evacuate");
    }

    @Test
    @DisplayName("a guided alert loses its guidance once replaced; its live replacement keeps it")
    void supersessionStripsGuidanceThatWouldOtherwiseShow() {
        NormalizedAlert original = guided("urn:oid:G-OLD");
        NormalizedAlert update = TestAlerts.nws("Flood Warning")
                .id("urn:oid:G-NEW").messageType("Update")
                .references(List.of("urn:oid:G-OLD")).endsAt(FUTURE).build();
        ingest.setSnapshotForTest(List.of(original, update));

        List<AlertCardDto> cards = feed.feedFor(LAT, LNG).alerts();
        // Control: the template really does produce guidance for this product.
        assertThat(cardFor(cards, "urn:oid:G-NEW").whatToDo()).isNotNull();
        assertThat(cardFor(cards, "urn:oid:G-OLD").whatToDo()).isNull();
        assertThat(cardFor(cards, "urn:oid:G-OLD").precautions()).isNull();
    }

    @Test
    @DisplayName("replaced by a Cancel in the same snapshot: cancelled, not superseded")
    void cancelledInTheSameSnapshotSaysCancelled() {
        NormalizedAlert original = evacuation("urn:oid:C-OLD");
        NormalizedAlert cancel = TestAlerts.nws("Flood Warning")
                .id("urn:oid:C-CANCEL").messageType("Cancel")
                .references(List.of("urn:oid:C-OLD")).endsAt(FUTURE).build();
        ingest.setSnapshotForTest(List.of(original, cancel));

        List<AlertCardDto> cards = feed.feedFor(LAT, LNG).alerts();
        AlertCardDto old = cardFor(cards, "urn:oid:C-OLD");
        assertThat(old.lifecycleState()).isEqualTo("cancelled");
        assertThat(json(old).path("safety").path("movementDirective").asText()).isEqualTo("none");
        // The Cancel itself is a retraction, never a card.
        assertThat(cards).extracting(AlertCardDto::id).doesNotContain("urn:oid:C-CANCEL");
    }

    @Test
    @DisplayName("an unreplaced live alert is unchanged")
    void unreplacedLiveAlertIsUnchanged() {
        ingest.setSnapshotForTest(List.of(guided("urn:oid:SOLO")));
        AlertCardDto card = cardFor(feed.feedFor(LAT, LNG).alerts(), "urn:oid:SOLO");
        assertThat(card.lifecycleState()).isEqualTo("active");
        assertThat(card.whatToDo()).isNotNull();
        assertThat(card.supersededBy()).isNull();
    }

    // ------------------------------------------------------------------
    // (d) template + decision once per alert
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the feed resolves each alert's template and decision exactly once")
    void templateAndDecisionAreComputedOncePerAlert() {
        List<NormalizedAlert> snapshot = List.of(
                guided("urn:oid:A"),
                evacuation("urn:oid:B"),
                TestAlerts.nws("Flash Flood Warning").id("urn:oid:C").endsAt(FUTURE).build(),
                TestAlerts.nwsWatch("Flood Watch").id("urn:oid:D").endsAt(FUTURE).build());
        ingest.setSnapshotForTest(snapshot);

        AlertFeedService counted = spy(feed);
        int served = counted.feedFor(LAT, LNG).alerts().size();
        assertThat(served).as("every alert in the snapshot matches here").isEqualTo(snapshot.size());

        // One template lookup and one policy decision per alert — the filter,
        // the card and isLifeThreatening all read the same Assessment.
        verify(dispatch, times(snapshot.size())).matchForAlert(any());
        verify(counted, times(snapshot.size())).assess(any());
    }
}
