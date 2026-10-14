package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.AlertInterpretationSnapshot;
import io.sitprep.sitprepapi.domain.AlertSourceRevision;
import io.sitprep.sitprepapi.dto.AlertCardDto;
import io.sitprep.sitprepapi.repo.AlertInterpretationSnapshotRepo;
import io.sitprep.sitprepapi.repo.AlertSourceRevisionRepo;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import io.sitprep.sitprepapi.util.AlertSourceFingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.*;

/**
 * S2 C3 — {@link InterpretationRecorder} (contract §6): idempotent, bounded by
 * distinct revisions, honest about which sources were actually polled, and the
 * source of the feed card's {@code interpretationRef} without a query.
 *
 * <p>The repositories are an in-memory fake behind Mockito, so every database
 * call is countable; the real feed, dispatch templates and policy build the
 * interpretation. The SQL itself is exercised on H2
 * ({@code AlertInterpretationPersistenceTest}) and on the replayed Postgres
 * schema ({@code AlertInterpretationPostgresIT}).</p>
 */
class InterpretationRecorderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-10-14T12:00:00Z");
    private static final Set<String> SAN_CARLOS = Set.of("AZZ560", "AZC007", "AZZ133");
    private static final double LAT = 33.3172, LNG = -110.5297;

    private AlertSourceRevisionRepo revisions;
    private AlertInterpretationSnapshotRepo snapshots;
    private final Map<String, AlertSourceRevision> revisionRows = new LinkedHashMap<>();
    private final Map<UUID, AlertInterpretationSnapshot> snapshotRows = new LinkedHashMap<>();

    private AlertIngestService ingest;
    private AlertFeedService feed;
    private InterpretationRecorder recorder;

    @BeforeEach
    void setUp() {
        revisions = mock(AlertSourceRevisionRepo.class);
        snapshots = mock(AlertInterpretationSnapshotRepo.class);
        fakeStore();

        NwsZoneService zones = new NwsZoneService();
        zones.seedPointZones(LAT, LNG, SAN_CARLOS);
        ingest = new AlertIngestService(zones);
        AlertDispatchService dispatch = new AlertDispatchService(null, null, null, null, null, null, zones, null);
        dispatch.loadTemplates();

        recorder = newRecorder(64);
        feed = new AlertFeedService(ingest, dispatch, null, recorder);
        at(T0);
    }

    private InterpretationRecorder newRecorder(int cacheSize) {
        InterpretationRecorder r = new InterpretationRecorder(revisions, snapshots, () -> feed, cacheSize);
        r.clock = Clock.fixed(T0, ZoneOffset.UTC);
        return r;
    }

    private void at(Instant t) {
        recorder.clock = Clock.fixed(t, ZoneOffset.UTC);
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private static NormalizedAlert flood() {
        return TestAlerts.nws("Flood Warning")
                .id("urn:oid:2.49.0.1.840.0.flood.001.1")
                .sent("2026-10-14T11:55:00-07:00")
                .references(List.of("urn:oid:2.49.0.1.840.0.flood.000.1"))
                .endsAt("2099-08-27T06:55:00Z")
                .build();
    }

    private static NormalizedAlert quake(double magnitude) {
        return TestAlerts.usgs("M " + magnitude + " - 10 km N of Somewhere, CA")
                .id("us7000abc")
                .magnitude(magnitude)
                .geometry(Map.of("type", "Point", "coordinates", List.of(-118.2, 34.0)))
                .build();
    }

    /** Every source answered at {@code at}. */
    private static Snapshot polled(Instant at, NormalizedAlert... alerts) {
        return new Snapshot(List.of(alerts), at, at);
    }

    /** Only {@code freshSources} answered at {@code at}; the rest last answered at {@code before}. */
    private static Snapshot partly(Instant at, Instant before, Set<String> freshSources, NormalizedAlert... alerts) {
        Map<String, Instant> last = new LinkedHashMap<>();
        for (String s : List.of("NWS", "USGS", "FEMA")) last.put(s, freshSources.contains(s) ? at : before);
        return new Snapshot(List.of(alerts), at, before, last, freshSources);
    }

    // ── idempotence ─────────────────────────────────────────────────────

    @Test
    void twoIdenticalPollsWriteOnce_andTheSecondNeverTouchesTheDatabase() {
        Snapshot tick = polled(T0, flood(), quake(5.9));

        InterpretationRecorder.Result first = recorder.record(tick);
        assertThat(first.newRevisions()).isEqualTo(2);
        assertThat(first.newSnapshots()).isEqualTo(2);
        assertThat(revisionRows).hasSize(2);
        assertThat(snapshotRows).hasSize(2);

        clearInvocations(revisions, snapshots);
        at(T0.plus(Duration.ofMinutes(5)));
        InterpretationRecorder.Result second = recorder.record(polled(T0.plus(Duration.ofMinutes(5)), flood(), quake(5.9)));

        assertThat(second).isEqualTo(new InterpretationRecorder.Result(0, 0, 0));
        assertThat(revisionRows).hasSize(2);
        assertThat(snapshotRows).hasSize(2);
        verifyNoInteractions(revisions, snapshots);
    }

    @Test
    void aUsgsMagnitudeRevisionIsOneNewRevisionAndOneNewSnapshot() {
        recorder.record(polled(T0, flood(), quake(5.9)));
        at(T0.plus(Duration.ofMinutes(5)));

        InterpretationRecorder.Result r = recorder.record(polled(T0.plus(Duration.ofMinutes(5)), flood(), quake(6.2)));

        assertThat(r.newRevisions()).isEqualTo(1);
        assertThat(r.newSnapshots()).isEqualTo(1);
        List<AlertSourceRevision> quakes = revisionRows.values().stream()
                .filter(x -> "USGS:us7000abc".equals(x.getDedupeKey())).toList();
        assertThat(quakes).hasSize(2);
        assertThat(quakes).extracting(AlertSourceRevision::getRevisionRef)
                .containsExactlyInAnyOrder(AlertSourceFingerprint.revisionRef(quake(5.9)),
                        AlertSourceFingerprint.revisionRef(quake(6.2)));
        assertThat(snapshotRows.values()).extracting(AlertInterpretationSnapshot::getRevisionRef)
                .contains(AlertSourceFingerprint.revisionRef(quake(6.2)));
    }

    @Test
    void aRestartFindsTheStoredRowsInOneBatchedLookupAndInsertsNothing() {
        recorder.record(polled(T0, flood(), quake(5.9)));
        clearInvocations(revisions, snapshots);

        InterpretationRecorder restarted = newRecorder(64);
        InterpretationRecorder.Result r = restarted.record(polled(T0, flood(), quake(5.9)));

        assertThat(r).isEqualTo(new InterpretationRecorder.Result(0, 0, 0));
        verify(revisions, times(1)).findAllById(anyIterable());
        verify(snapshots, times(1)).findIdsByFingerprintIn(anyCollection());
        verify(revisions, never()).saveAll(anyIterable());
        verify(snapshots, never()).saveAll(anyIterable());

        clearInvocations(revisions, snapshots);
        restarted.record(polled(T0, flood(), quake(5.9)));
        verifyNoInteractions(revisions, snapshots);
    }

    // ── the freshly-polled rule ─────────────────────────────────────────

    @Test
    void lastSeenMovesOnlyForAFreshlyPolledSource_andAtMostOncePerStep() {
        recorder.record(polled(T0, flood(), quake(5.9)));
        String floodRef = AlertSourceFingerprint.revisionRef(flood());
        String quakeRef = AlertSourceFingerprint.revisionRef(quake(5.9));

        // Within the step: a fresh re-listing costs nothing.
        Instant soon = T0.plus(InterpretationRecorder.LAST_SEEN_STEP).minusSeconds(60);
        at(soon);
        clearInvocations(revisions, snapshots);
        recorder.record(polled(soon, flood(), quake(5.9)));
        verifyNoInteractions(revisions, snapshots);

        // Past the step, NWS failed this tick: its alert rode forward and is not a sighting.
        Instant later = T0.plus(Duration.ofHours(2));
        at(later);
        InterpretationRecorder.Result r = recorder.record(partly(later, T0, Set.of("USGS", "FEMA"), flood(), quake(5.9)));

        assertThat(r.touchedRevisions()).isEqualTo(1);
        verify(revisions).touchLastSeen(List.of(quakeRef), later);
        assertThat(revisionRows.get(quakeRef).getLastSeenAt()).isEqualTo(later);
        assertThat(revisionRows.get(floodRef).getLastSeenAt()).isEqualTo(T0);
    }

    @Test
    void aNewRevisionFromASourceThatFailedThisTickIsStampedWithThatSourcesLastSuccess() {
        Instant later = T0.plus(Duration.ofHours(2));
        at(later);

        recorder.record(partly(later, T0, Set.of("USGS", "FEMA"), flood()));

        AlertSourceRevision row = revisionRows.get(AlertSourceFingerprint.revisionRef(flood()));
        assertThat(row.getFirstSeenAt()).isEqualTo(T0);
        assertThat(row.getLastSeenAt()).isEqualTo(T0);
    }

    // ── the LRU ──────────────────────────────────────────────────────────

    @Test
    void theCachesAreBounded_andAnEvictedKeyCostsALookupNotARow() {
        InterpretationRecorder small = newRecorder(16);   // the floor
        List<NormalizedAlert> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) many.add(quake(4.0 + i / 10.0));

        small.record(new Snapshot(List.copyOf(many), T0, T0));
        assertThat(revisionRows).hasSize(20);
        assertThat(small.cacheSizes()[0]).isEqualTo(16);
        assertThat(small.cacheSizes()[1]).isEqualTo(16);

        clearInvocations(revisions, snapshots);
        small.record(polled(T0, many.get(0)));       // the eldest, evicted
        verify(revisions, times(1)).findAllById(anyIterable());
        verify(revisions, never()).saveAll(anyIterable());
        verify(snapshots, never()).saveAll(anyIterable());
        assertThat(revisionRows).hasSize(20);
    }

    // ── what a snapshot holds ───────────────────────────────────────────

    @Test
    void aSnapshotHoldsTheInterpretationAndDecision_neverTheViewerOrCounts() throws Exception {
        recorder.record(polled(T0, flood()));

        AlertInterpretationSnapshot s = snapshotRows.values().iterator().next();
        assertThat(s.getRevisionRef()).isEqualTo(AlertSourceFingerprint.revisionRef(flood()));
        assertThat(s.getDedupeKey()).isEqualTo("NWS:urn:oid:2.49.0.1.840.0.flood.001.1");
        assertThat(s.getPolicyVersion()).isEqualTo(AlertSafetyPolicy.POLICY_VERSION);
        assertThat(s.getInterpreterVersion()).isEqualTo(AlertInterpreter.VERSION);
        assertThat(s.getFingerprint()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(s.getCreatedAt()).isEqualTo(T0);

        JsonNode content = MAPPER.readTree(s.getContentJson());
        List<String> keys = new ArrayList<>();
        content.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactly("interpreter", "presentation", "sourceIdentity", "lifecycleInfo");
        assertThat(s.getContentJson()).doesNotContain("viewerContext", "communityThreads", "postIds", "actions");
        assertThat(content.path("sourceIdentity").path("key").asText()).isEqualTo("NWS");

        JsonNode decision = MAPPER.readTree(s.getDecisionJson());
        assertThat(decision.has("dispatchMode")).isTrue();
        assertThat(decision.has("guidanceMode")).isTrue();
        assertThat(decision.has("compatibility")).isTrue();
        assertThat(decision.has("capActions")).isTrue();
        assertThat(decision.has("movementDirective")).isTrue();
        assertThat(decision.has("asIssuedMovementDirective")).isTrue();
        assertThat(decision.has("reason")).isTrue();

        AlertSourceRevision rev = revisionRows.get(s.getRevisionRef());
        assertThat(rev.getSource()).isEqualTo("NWS");
        assertThat(rev.getUpstreamId()).isEqualTo("urn:oid:2.49.0.1.840.0.flood.001.1");
        assertThat(rev.getReferenceIds()).isEqualTo("urn:oid:2.49.0.1.840.0.flood.000.1");
        assertThat(rev.getSentAt()).isEqualTo(Instant.parse("2026-10-14T18:55:00Z"));
        assertThat(rev.getPayload()).contains("\"id\":\"urn:oid:2.49.0.1.840.0.flood.001.1\"");
    }

    // ── interpretationRef on the feed ───────────────────────────────────

    @Test
    void theFeedCardCarriesTheRecordedSnapshotId_withoutAQueryPerCard() {
        ingest.setSnapshotForTest(List.of(flood()));
        AlertCardDto before = feed.feedForViewer(LAT, LNG, null, "current").alerts().get(0);
        assertThat(before.interpretationRef()).as("nothing recorded yet").isNull();

        recorder.record(ingest.getSnapshot());
        UUID recorded = snapshotRows.keySet().iterator().next();
        clearInvocations(revisions, snapshots);

        AlertCardDto card = feed.feedForViewer(LAT, LNG, null, "current").alerts().get(0);
        assertThat(card.interpretationRef()).isEqualTo(recorded.toString());
        assertThat(card.sourceRevisionRef()).isEqualTo(snapshotRows.get(recorded).getRevisionRef());
        verifyNoInteractions(revisions, snapshots);

        // The internal decision feed builds no interpretation and asks for none.
        assertThat(feed.feedFor(LAT, LNG).alerts().get(0).interpretationRef()).isNull();
    }

    @Test
    void aSweptSnapshotLeavesTheCache_soNoCardPointsAtIt() {
        ingest.setSnapshotForTest(List.of(flood()));
        recorder.record(ingest.getSnapshot());
        UUID recorded = snapshotRows.keySet().iterator().next();
        when(snapshots.findSweepableIds(any(), any())).thenReturn(List.of(recorded));
        when(snapshots.deleteSweepable(anyCollection())).thenReturn(1);

        assertThat(recorder.sweepSnapshotsOnce(30, 1000)).isEqualTo(1);

        assertThat(feed.feedForViewer(LAT, LNG, null, "current").alerts().get(0).interpretationRef()).isNull();
    }

    @Test
    void aRecordingFailureIsSwallowedByTheIngestHook() {
        when(revisions.findAllById(anyIterable())).thenThrow(new IllegalStateException("db away"));

        recorder.onPoll(polled(T0, flood()));   // must not throw

        assertThat(snapshotRows).isEmpty();
    }

    // ── the in-memory store ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void fakeStore() {
        when(revisions.findAllById(anyIterable())).thenAnswer(inv -> {
            List<AlertSourceRevision> out = new ArrayList<>();
            for (String id : (Iterable<String>) inv.getArgument(0)) {
                if (revisionRows.containsKey(id)) out.add(revisionRows.get(id));
            }
            return out;
        });
        when(revisions.saveAll(anyIterable())).thenAnswer(inv -> {
            List<AlertSourceRevision> out = new ArrayList<>();
            for (AlertSourceRevision r : (Iterable<AlertSourceRevision>) inv.getArgument(0)) {
                assertThat(revisionRows).as("a revision is inserted once").doesNotContainKey(r.getRevisionRef());
                revisionRows.put(r.getRevisionRef(), r);
                out.add(r);
            }
            return out;
        });
        when(revisions.touchLastSeen(anyCollection(), any())).thenAnswer(inv -> {
            Instant at = inv.getArgument(1);
            int n = 0;
            for (String ref : (Collection<String>) inv.getArgument(0)) {
                AlertSourceRevision r = revisionRows.get(ref);
                if (r != null && r.getLastSeenAt().isBefore(at)) {
                    r.setLastSeenAt(at);
                    n++;
                }
            }
            return n;
        });
        when(snapshots.findIdsByFingerprintIn(anyCollection())).thenAnswer(inv -> {
            Collection<String> fps = inv.getArgument(0);
            List<AlertInterpretationSnapshotRepo.IdByFingerprint> out = new ArrayList<>();
            for (AlertInterpretationSnapshot s : snapshotRows.values()) {
                if (fps.contains(s.getFingerprint())) {
                    UUID id = s.getId();
                    String fp = s.getFingerprint();
                    out.add(new AlertInterpretationSnapshotRepo.IdByFingerprint() {
                        public UUID getId() { return id; }
                        public String getFingerprint() { return fp; }
                    });
                }
            }
            return out;
        });
        when(snapshots.saveAll(anyIterable())).thenAnswer(inv -> {
            List<AlertInterpretationSnapshot> out = new ArrayList<>();
            for (AlertInterpretationSnapshot s : (Iterable<AlertInterpretationSnapshot>) inv.getArgument(0)) {
                assertThat(revisionRows).as("FK: the revision exists first").containsKey(s.getRevisionRef());
                assertThat(snapshotRows.values()).extracting(AlertInterpretationSnapshot::getFingerprint)
                        .as("fingerprint is unique").doesNotContain(s.getFingerprint());
                snapshotRows.put(s.getId(), s);
                out.add(s);
            }
            return out;
        });
    }
}
