package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.AlertHistory;
import io.sitprep.sitprepapi.dto.AlertCardDto;
import io.sitprep.sitprepapi.dto.AlertHistoryResponse;
import io.sitprep.sitprepapi.repo.AlertHistoryRepo;
import io.sitprep.sitprepapi.service.AlertIngestService.CapReference;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import io.sitprep.sitprepapi.util.Vtec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * S2 C4 — {@code /api/alerts/history} groups, and the ending message that
 * C2's {@code toRecordCard(…, endedBy)} needs to say HOW a record ended.
 *
 * <p>Groups organise the cards; they never hide one. Every card is in exactly
 * one lifecycle group, lifecycle groups come first, and the row limit cuts
 * between whole groups.</p>
 */
class AlertHistoryGroupsTest {

    private static final Set<String> SAN_CARLOS = Set.of("AZZ560", "AZC007", "AZZ133");
    private static final double LAT = 33.3172, LNG = -110.5297;
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final String FUTURE = "2099-01-01T00:00:00Z";
    private static final String ENDED = "2026-10-10T18:00:00Z";

    private AlertDispatchService dispatch;
    private AlertFeedService feed;
    private AlertHistoryService history;
    private AlertHistoryRepo repo;
    private Map<String, AlertHistory> table;

    @BeforeEach
    void setUp() {
        NwsZoneService zones = new NwsZoneService();
        zones.seedPointZones(LAT, LNG, SAN_CARLOS);
        dispatch = new AlertDispatchService(null, null, null, null, null, null, zones, null);
        dispatch.loadTemplates();

        AlertIngestService ingest = mock(AlertIngestService.class);
        when(ingest.getSnapshot()).thenReturn(new Snapshot(List.of(), NOW, NOW));
        when(ingest.zoneCodesForPoint(anyDouble(), anyDouble())).thenReturn(SAN_CARLOS);
        feed = new AlertFeedService(ingest, dispatch);
        feed.clock = Clock.fixed(NOW, ZoneOffset.UTC);

        table = new LinkedHashMap<>();
        repo = inMemoryRepo();
        history = new AlertHistoryService(repo, ingest, dispatch, feed);
        history.clock = Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static Vtec vtec(String action, String key) {
        String[] p = key.split("\\.");
        return new Vtec(action, p[0], p[1], p[2], p[3], null, null, Integer.parseInt(p[4]));
    }

    private static CapReference ref(String id, String sent) {
        return new CapReference("w-nws.webmaster@noaa.gov", id, sent);
    }

    /** Stored as the recorder would, last seen {@code minutesAgo} before NOW (newer rows list first). */
    private void store(NormalizedAlert a, int minutesAgo) {
        AlertHistory row = history.toRow(a, NOW.minusSeconds(60L * minutesAgo));
        table.put(row.getAlertId(), row);
    }

    /** A NEW → CON → EXP event, each message naming the earlier ones; sent hours apart on the 10th. */
    private List<NormalizedAlert> vtecEvent(String prefix, String vtecKey, int startHour) {
        String s1 = String.format("2026-10-10T%02d:00:00Z", startHour);
        String s2 = String.format("2026-10-10T%02d:00:00Z", startHour + 1);
        String s3 = String.format("2026-10-10T%02d:00:00Z", startHour + 2);
        NormalizedAlert n = TestAlerts.nws("Flood Warning").id(prefix + ".NEW").sent(s1).endsAt(ENDED)
                .vtec(List.of(vtec("NEW", vtecKey))).build();
        NormalizedAlert c = TestAlerts.nws("Flood Warning").id(prefix + ".CON").sent(s2).endsAt(ENDED)
                .messageType("Update").vtec(List.of(vtec("CON", vtecKey)))
                .referenceTuples(List.of(ref(prefix + ".NEW", s1))).build();
        NormalizedAlert e = TestAlerts.nws("Flood Warning").id(prefix + ".EXP").sent(s3).endsAt(ENDED)
                .messageType("Update").vtec(List.of(vtec("EXP", vtecKey)))
                .referenceTuples(List.of(ref(prefix + ".CON", s2), ref(prefix + ".NEW", s1))).build();
        return List.of(n, c, e);
    }

    private static AlertHistoryResponse.Group groupOf(AlertHistoryResponse r, String dedupeKey) {
        return r.groups().stream()
                .filter(g -> AlertHistoryResponse.GROUP_LIFECYCLE.equals(g.kind()))
                .filter(g -> g.members().contains(dedupeKey))
                .findFirst().orElseThrow(() -> new AssertionError("no lifecycle group holds " + dedupeKey));
    }

    private static void assertPartition(AlertHistoryResponse r) {
        List<String> cardKeys = r.alerts().stream().map(AlertCardDto::dedupeKey).toList();
        List<String> grouped = new ArrayList<>();
        for (AlertHistoryResponse.Group g : r.groups()) {
            if (!AlertHistoryResponse.GROUP_LIFECYCLE.equals(g.kind())) continue;
            assertThat(g.count()).isEqualTo(g.members().size());
            assertThat(g.members()).contains(g.headMember());
            grouped.addAll(g.members());
        }
        assertThat(grouped).as("every card in exactly one lifecycle group, and nothing else")
                .containsExactlyInAnyOrderElementsOf(cardKeys);
    }

    @Test
    @DisplayName("a VTEC event is one lifecycle group, oldest first, headed by its newest message")
    void aVtecEventIsOneGroup() {
        List<NormalizedAlert> ev = vtecEvent("urn:oid:G1", "KSLC.FL.W.0011.2026", 8);
        store(ev.get(0), 300);
        store(ev.get(1), 200);
        store(ev.get(2), 100);
        NormalizedAlert lone = TestAlerts.nws("Heat Advisory").id("urn:oid:LONE")
                .sent("2026-10-12T08:00:00Z").endsAt(ENDED).build();
        store(lone, 50);

        AlertHistoryResponse r = history.historyFor(LAT, LNG, 30);

        assertThat(r.groups()).isNotNull();
        assertPartition(r);
        AlertHistoryResponse.Group g = groupOf(r, "NWS:urn:oid:G1.NEW");
        assertThat(g.kind()).isEqualTo("lifecycle");
        assertThat(g.threadKey()).isEqualTo("VTEC:KSLC.FL.W.0011.2026");
        assertThat(g.members()).containsExactly("NWS:urn:oid:G1.NEW", "NWS:urn:oid:G1.CON", "NWS:urn:oid:G1.EXP");
        assertThat(g.headMember()).isEqualTo("NWS:urn:oid:G1.EXP");
        assertThat(g.count()).isEqualTo(3);

        AlertHistoryResponse.Group single = groupOf(r, "NWS:urn:oid:LONE");
        assertThat(single.count()).isEqualTo(1);
        assertThat(single.headMember()).isEqualTo("NWS:urn:oid:LONE");
        assertThat(single.threadKey()).isEqualTo("CAP:urn:oid:LONE");

        // Lifecycle groups first; the most recently seen thread leads.
        assertThat(r.groups().get(0).members()).containsExactly("NWS:urn:oid:LONE");
    }

    @Test
    @DisplayName("a CAP chain whose messages each name only their predecessor is still one group")
    void aCapChainIsOneGroup() {
        NormalizedAlert x = TestAlerts.nws("Special Weather Statement").id("urn:oid:CX")
                .sent("2026-10-10T08:00:00Z").endsAt(ENDED).build();
        NormalizedAlert y = TestAlerts.nws("Special Weather Statement").id("urn:oid:CY")
                .sent("2026-10-10T09:00:00Z").endsAt(ENDED).messageType("Update")
                .referenceTuples(List.of(ref("urn:oid:CX", "2026-10-10T08:00:00Z"))).build();
        NormalizedAlert z = TestAlerts.nws("Special Weather Statement").id("urn:oid:CZ")
                .sent("2026-10-10T10:00:00Z").endsAt(ENDED).messageType("Update")
                .referenceTuples(List.of(ref("urn:oid:CY", "2026-10-10T09:00:00Z"))).build();
        store(x, 300);
        store(y, 200);
        store(z, 100);

        AlertHistoryResponse r = history.historyFor(LAT, LNG, 30);
        assertPartition(r);
        AlertHistoryResponse.Group g = groupOf(r, "NWS:urn:oid:CZ");
        assertThat(g.members()).containsExactly("NWS:urn:oid:CX", "NWS:urn:oid:CY", "NWS:urn:oid:CZ");
        assertThat(g.threadKey()).as("the oldest member's key: the furthest back the thread is known")
                .isEqualTo("CAP:urn:oid:CX");
    }

    @Test
    @DisplayName("a presentation cluster spans threads, comes after them, and hides nobody")
    void clustersComeAfterAndHideNothing() {
        // Two independent alerts for the same product, zones and onset hour.
        NormalizedAlert a = TestAlerts.nws("Heat Advisory").id("urn:oid:H1").startedAt("2026-10-12T12:00:00Z")
                .sent("2026-10-12T11:00:00Z").endsAt(ENDED).build();
        NormalizedAlert b = TestAlerts.nws("Heat Advisory").id("urn:oid:H2").startedAt("2026-10-12T12:30:00Z")
                .sent("2026-10-12T11:30:00Z").endsAt(ENDED).build();
        store(a, 200);
        store(b, 100);

        AlertHistoryResponse r = history.historyFor(LAT, LNG, 30);
        assertPartition(r);
        assertThat(r.alerts()).hasSize(2);

        List<AlertHistoryResponse.Group> clusters = r.groups().stream()
                .filter(g -> "cluster".equals(g.kind())).toList();
        assertThat(clusters).hasSize(1);
        AlertHistoryResponse.Group c = clusters.get(0);
        assertThat(c.members()).containsExactly("NWS:urn:oid:H1", "NWS:urn:oid:H2");
        assertThat(c.threadKey()).isEqualTo(r.alerts().get(0).clusterKey());
        assertThat(c.headMember()).isEqualTo("NWS:urn:oid:H2");
        // Each is still its own thread; the cluster is listed last.
        assertThat(groupOf(r, "NWS:urn:oid:H1").members()).containsExactly("NWS:urn:oid:H1");
        assertThat(groupOf(r, "NWS:urn:oid:H2").members()).containsExactly("NWS:urn:oid:H2");
        assertThat(r.groups().get(r.groups().size() - 1).kind()).isEqualTo("cluster");
    }

    @Test
    @DisplayName("updates in ONE thread sharing a clusterKey make no cluster group")
    void aClusterInsideOneThreadIsNotRepeated() {
        List<NormalizedAlert> ev = vtecEvent("urn:oid:G2", "KSLC.FL.W.0012.2026", 8);
        store(ev.get(0), 300);
        store(ev.get(1), 200);
        store(ev.get(2), 100);
        AlertHistoryResponse r = history.historyFor(LAT, LNG, 30);
        assertThat(r.groups()).allSatisfy(g -> assertThat(g.kind()).isEqualTo("lifecycle"));
    }

    @Test
    @DisplayName("the row limit applies after grouping: whole groups, never a split one")
    void theLimitNeverSplitsAGroup() {
        // Newest first by last sighting: LONE (1), then a 3-message thread, then OLD (1).
        NormalizedAlert lone = TestAlerts.nws("Heat Advisory").id("urn:oid:L1")
                .sent("2026-10-12T08:00:00Z").endsAt(ENDED).build();
        List<NormalizedAlert> ev = vtecEvent("urn:oid:G3", "KSLC.FL.W.0013.2026", 8);
        NormalizedAlert old = TestAlerts.nws("Wind Advisory").id("urn:oid:O1")
                .sent("2026-10-09T08:00:00Z").endsAt(ENDED).build();
        store(lone, 10);
        store(ev.get(2), 20);
        store(ev.get(1), 30);
        store(ev.get(0), 40);
        store(old, 50);

        // Room for four: the lone alert and the whole thread; the old alert would be a fifth.
        history.maxRows = 4;
        AlertHistoryResponse four = history.historyFor(LAT, LNG, 30);
        assertPartition(four);
        assertThat(four.alerts()).extracting(AlertCardDto::id)
                .containsExactly("urn:oid:L1", "urn:oid:G3.EXP", "urn:oid:G3.CON", "urn:oid:G3.NEW");

        // Room for three: the thread does not fit whole, so it is not shown at
        // all — and nothing older is shown in its place.
        history.maxRows = 3;
        AlertHistoryResponse three = history.historyFor(LAT, LNG, 30);
        assertPartition(three);
        assertThat(three.alerts()).extracting(AlertCardDto::id).containsExactly("urn:oid:L1");

        // A first group larger than the limit is shown whole rather than not at all.
        store(lone, 60);   // now the thread leads
        history.maxRows = 2;
        AlertHistoryResponse two = history.historyFor(LAT, LNG, 30);
        assertPartition(two);
        assertThat(two.alerts()).extracting(AlertCardDto::id)
                .containsExactly("urn:oid:G3.EXP", "urn:oid:G3.CON", "urn:oid:G3.NEW");
    }

    @Test
    @DisplayName("a cancelled record says how it ended: all_clear versus cap_cancel")
    void theEndingMessageReachesTheRecordCard() {
        NormalizedAlert a1 = TestAlerts.nws("Flood Warning").id("urn:oid:AC1")
                .sent("2026-10-14T08:00:00Z").endsAt(FUTURE).build();
        NormalizedAlert allClear = TestAlerts.nws("Flood Warning").id("urn:oid:AC1-END").messageType("Update")
                .response("AllClear").sent("2026-10-14T09:00:00Z").references(List.of("urn:oid:AC1"))
                .endsAt(FUTURE).build();
        NormalizedAlert a2 = TestAlerts.nws("Flood Warning").id("urn:oid:CC1")
                .sent("2026-10-14T08:00:00Z").endsAt(FUTURE).build();
        NormalizedAlert cancel = TestAlerts.nws("Flood Warning").id("urn:oid:CC1-END").messageType("Cancel")
                .sent("2026-10-14T09:00:00Z").references(List.of("urn:oid:CC1")).endsAt(FUTURE).build();
        store(a1, 120);
        store(allClear, 60);
        store(a2, 120);
        store(cancel, 60);

        AlertHistoryResponse r = history.historyFor(LAT, LNG, 30);
        AlertCardDto cleared = card(r, "urn:oid:AC1");
        assertThat(cleared.lifecycleState()).isEqualTo("cancelled");
        assertThat(cleared.lifecycleInfo().verified()).isTrue();
        assertThat(cleared.lifecycleInfo().reason()).isEqualTo("all_clear");
        assertThat(cleared.lifecycleInfo().replacedBy()).isEqualTo("NWS:urn:oid:AC1-END");
        assertThat(cleared.presentation().tone()).isEqualTo("all_clear");

        AlertCardDto cancelled = card(r, "urn:oid:CC1");
        assertThat(cancelled.lifecycleState()).isEqualTo("cancelled");
        assertThat(cancelled.lifecycleInfo().reason()).isEqualTo("cap_cancel");
        assertThat(cancelled.presentation().tone()).isEqualTo("neutral");
        assertThat(cancelled.interpreter().primaryAction()).isNull();

        // The Cancel is an edge, never a card; the AllClear Update is a card of its own.
        assertThat(r.alerts()).extracting(AlertCardDto::id).doesNotContain("urn:oid:CC1-END");
        assertPartition(r);
    }

    @Test
    @DisplayName("an alert named by two later messages was ended by the first of them")
    void theImmediateSuccessorEndsIt() {
        List<NormalizedAlert> ev = vtecEvent("urn:oid:G4", "KSLC.FL.W.0014.2026", 8);
        store(ev.get(0), 300);
        store(ev.get(1), 200);
        store(ev.get(2), 100);
        AlertHistoryResponse r = history.historyFor(LAT, LNG, 30);
        assertThat(card(r, "urn:oid:G4.NEW").lifecycleInfo().replacedBy()).isEqualTo("NWS:urn:oid:G4.CON");
        assertThat(card(r, "urn:oid:G4.CON").lifecycleInfo().replacedBy()).isEqualTo("NWS:urn:oid:G4.EXP");
    }

    @Test
    @DisplayName("grouping costs no query: one candidate read, one recording-since read")
    void groupingIsFree() {
        for (int i = 0; i < 6; i++) {
            List<NormalizedAlert> ev = vtecEvent("urn:oid:Q" + i, "KSLC.FL.W.00" + (20 + i) + ".2026", 8);
            for (NormalizedAlert a : ev) store(a, 100 + i);
        }
        clearInvocations(repo);
        AlertHistoryResponse r = history.historyFor(LAT, LNG, 30);
        assertThat(r.groups()).filteredOn(g -> "lifecycle".equals(g.kind())).hasSize(6);
        verify(repo, times(1)).findCandidates(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), any());
        verify(repo, times(1)).findRecordingSince();
        verifyNoMoreInteractions(repo);
    }

    private static AlertCardDto card(AlertHistoryResponse r, String id) {
        return r.alerts().stream().filter(c -> id.equals(c.id())).findFirst()
                .orElseThrow(() -> new AssertionError("no card " + id));
    }

    private AlertHistoryRepo inMemoryRepo() {
        AlertHistoryRepo r = mock(AlertHistoryRepo.class);
        when(r.findCandidates(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), any()))
                .thenAnswer(inv -> {
                    Instant since = inv.getArgument(0);
                    Collection<String> tokens = inv.getArgument(5);
                    Set<String> wanted = new HashSet<>(tokens);
                    return table.values().stream()
                            .filter(row -> row.getLastSeenAt().isAfter(since))
                            .filter(row -> row.isBroadcast()
                                    || row.getTokens().stream().anyMatch(wanted::contains))
                            .sorted((p, q) -> q.getLastSeenAt().compareTo(p.getLastSeenAt()))
                            .toList();
                });
        when(r.findRecordingSince()).thenReturn(NOW.minusSeconds(86_400L * 30));
        return r;
    }
}
