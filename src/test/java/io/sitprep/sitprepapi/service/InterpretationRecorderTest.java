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
import org.mockito.stubbing.Answer;

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
        verify(revisions, times(1)).findLastSeenByRefIn(anyCollection());
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
        verify(revisions, times(1)).findLastSeenByRefIn(anyCollection());
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
        when(revisions.findLastSeenByRefIn(anyCollection())).thenThrow(new IllegalStateException("db away"));

        recorder.onPoll(polled(T0, flood()));   // must not throw

        assertThat(snapshotRows).isEmpty();
    }

    // ── review 1: a record never points at the live interpretation ──────

    /** The message that replaces {@link #flood()} inside the same snapshot. */
    private static NormalizedAlert floodUpdate() {
        return TestAlerts.nws("Flood Warning")
                .id("urn:oid:2.49.0.1.840.0.flood.002.1")
                .sent("2026-10-14T12:30:00-07:00")
                .references(List.of("urn:oid:2.49.0.1.840.0.flood.001.1"))
                .endsAt("2099-08-27T06:55:00Z")
                .build();
    }

    /** What a record card may never point at: a snapshot that speaks in the present tense. */
    private static void assertSaysNothingCurrent(AlertInterpretationSnapshot s) throws Exception {
        JsonNode content = MAPPER.readTree(s.getContentJson());
        assertThat(content.path("presentation").path("current").asBoolean())
                .as("presentation.current").isFalse();
        assertThat(content.path("lifecycleInfo").path("current").asBoolean())
                .as("lifecycleInfo.current").isFalse();
        JsonNode primary = content.path("interpreter").path("primaryAction");
        assertThat(primary.isMissingNode() || primary.isNull()).as("a non-current snapshot has no primary action").isTrue();
    }

    @Test
    void aHistoryRecordCardOfAStillLiveAlertNeverCarriesTheLiveSnapshotId() throws Exception {
        ingest.setSnapshotForTest(List.of(flood()));
        recorder.record(ingest.getSnapshot());
        AlertInterpretationSnapshot live = snapshotRows.values().iterator().next();
        JsonNode content = MAPPER.readTree(live.getContentJson());
        assertThat(content.path("presentation").path("current").asBoolean())
                .as("the recorded snapshot is the live interpretation").isTrue();
        assertThat(content.path("interpreter").path("primaryAction").isObject()).isTrue();

        AlertCardDto rec = feed.toRecordCard(flood(), AlertIngestService.MatchType.BROADCAST, 50, "active");

        assertThat(rec.presentation().current()).isFalse();
        assertThat(rec.interpreter().primaryAction()).isNull();
        assertThat(rec.interpretationRef())
                .as("a record card never points at a snapshot that carries current guidance")
                .isNull();
    }

    @Test
    void anAlertSupersededInTheSnapshotGetsItsOwnRecordSnapshot_neverTheLiveOne() throws Exception {
        recorder.record(polled(T0, flood()));
        UUID liveId = snapshotRows.keySet().iterator().next();

        ingest.setSnapshotForTest(List.of(flood(), floodUpdate()));
        InterpretationRecorder.Result r = recorder.record(ingest.getSnapshot());
        assertThat(r.newRevisions()).as("only the update is a new revision").isEqualTo(1);
        assertThat(r.newSnapshots()).as("the update, and the replaced message as a record").isEqualTo(2);

        AlertCardDto replaced = feed.feedForViewer(LAT, LNG, null, "current").alerts().stream()
                .filter(c -> "urn:oid:2.49.0.1.840.0.flood.001.1".equals(c.id())).findFirst().orElseThrow();
        assertThat(replaced.supersededBy()).isNotNull();
        assertThat(replaced.presentation().current()).isFalse();
        assertThat(replaced.interpretationRef()).isNotNull().isNotEqualTo(liveId.toString());
        assertSaysNothingCurrent(snapshotRows.get(UUID.fromString(replaced.interpretationRef())));

        // History, holding the same stored edge, finds the same record snapshot.
        AlertCardDto history = feed.toRecordCard(flood(), AlertIngestService.MatchType.BROADCAST, 50,
                replaced.lifecycleState(), null, floodUpdate());
        assertThat(history.interpretationRef()).isEqualTo(replaced.interpretationRef());
    }

    @Test
    void noRecordCardEverPointsAtASnapshotThatSpeaksInThePresentTense() throws Exception {
        ingest.setSnapshotForTest(List.of(flood(), floodUpdate()));
        recorder.record(polled(T0, flood()));        // flood alone first: its live snapshot exists
        recorder.record(ingest.getSnapshot());

        List<AlertCardDto> records = new ArrayList<>();
        feed.feedForViewer(LAT, LNG, null, "current").alerts().stream()
                .filter(c -> !c.presentation().current()).forEach(records::add);
        for (String state : List.of("active", "updated", "superseded", "cancelled", "expired", "no_longer_listed")) {
            records.add(feed.toRecordCard(flood(), AlertIngestService.MatchType.BROADCAST, 50, state));
            records.add(feed.toRecordCard(flood(), AlertIngestService.MatchType.BROADCAST, 50, state, null, floodUpdate()));
            records.add(feed.toRecordCard(floodUpdate(), AlertIngestService.MatchType.BROADCAST, 50, state));
        }

        assertThat(records).isNotEmpty();
        for (AlertCardDto c : records) {
            if (c.interpretationRef() == null) continue;
            assertSaysNothingCurrent(snapshotRows.get(UUID.fromString(c.interpretationRef())));
        }
    }

    @Test
    void dispatchResolvesTheLiveSnapshot_andARecordOnlyOnceOneIsRecorded() {
        recorder.record(polled(T0, flood()));
        UUID liveId = snapshotRows.keySet().iterator().next();
        AlertFeedService.Assessment assessed = feed.assess(flood());

        assertThat(recorder.liveSnapshotId(flood(), null, assessed)).isEqualTo(liveId.toString());
        assertThat(recorder.liveSnapshotId(flood(), floodUpdate(), assessed))
                .as("replaced, it is a record — and no record snapshot exists yet").isNull();

        recorder.record(polled(T0, flood(), floodUpdate()));
        String recordId = recorder.liveSnapshotId(flood(), floodUpdate(), assessed);
        assertThat(recordId).isNotNull().isNotEqualTo(liveId.toString());
        assertThat(snapshotRows.get(UUID.fromString(recordId)).getRevisionRef())
                .isEqualTo(AlertSourceFingerprint.revisionRef(flood()));
    }

    // ── review 1: the caches never claim a row that is not there ────────

    @Test
    void aTouchThatMatchesFewerRowsThanAskedForgetsThoseRefs_soTheNextTickRechecks() {
        recorder.record(polled(T0, flood()));
        String floodRef = AlertSourceFingerprint.revisionRef(flood());
        revisionRows.remove(floodRef);              // gone underneath the cache
        snapshotRows.clear();                       // and so (FK) its snapshot

        Instant later = T0.plus(Duration.ofHours(2));
        at(later);
        recorder.record(polled(later, flood()));    // the touch matches 0 of 1

        clearInvocations(revisions, snapshots);
        Instant next = later.plus(Duration.ofMinutes(5));
        at(next);
        InterpretationRecorder.Result r = recorder.record(polled(next, flood()));

        assertThat(r.newRevisions()).as("the missing row is written again, not assumed").isEqualTo(1);
        assertThat(r.newSnapshots()).as("and so is its snapshot").isEqualTo(1);
        assertThat(revisionRows).containsKey(floodRef);
        assertThat(snapshotRows).hasSize(1);
    }

    @Test
    void aFailedSnapshotBatchForgetsItsRevisions_soTheNextTickRechecks() {
        when(snapshots.saveAll(anyIterable())).thenThrow(new IllegalStateException("FK violation"));
        recorder.onPoll(polled(T0, flood()));        // swallowed
        assertThat(snapshotRows).isEmpty();

        reset(snapshots);
        fakeStore();
        clearInvocations(revisions, snapshots);
        at(T0.plus(Duration.ofMinutes(5)));
        recorder.record(polled(T0.plus(Duration.ofMinutes(5)), flood()));

        verify(revisions, atLeastOnce()).findLastSeenByRefIn(anyCollection());
        assertThat(snapshotRows).hasSize(1);
    }

    /** Polls until {@code t} is parked on a monitor or has finished — no sleeps, no timing guesses. */
    private static Thread.State settle(Thread t) throws InterruptedException {
        for (int i = 0; i < 5000; i++) {
            Thread.State s = t.getState();
            if (s == Thread.State.BLOCKED || s == Thread.State.TERMINATED) return s;
            Thread.sleep(1);
        }
        return t.getState();
    }

    /**
     * The interleaving the review found: a tick reads a revision as stored, the
     * sweep deletes it, and the tick then caches it — a cache that claims a row
     * the table no longer has, so the revision is never written again and its
     * next snapshot fails the FK. Driven step by step with latches.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aSweepNeverRunsBetweenATicksLookupAndItsCacheWrite() throws Exception {
        String floodRef = AlertSourceFingerprint.revisionRef(flood());
        Instant old = T0.minus(Duration.ofDays(40));
        AlertSourceRevision stale = new AlertSourceRevision();
        stale.setRevisionRef(floodRef);
        stale.setDedupeKey("NWS:urn:oid:2.49.0.1.840.0.flood.001.1");
        stale.setSource("NWS");
        stale.setUpstreamId("urn:oid:2.49.0.1.840.0.flood.001.1");
        stale.setFingerprint(AlertSourceFingerprint.fingerprint(flood()));
        stale.setPayload("{}");
        stale.setFirstSeenAt(old);
        stale.setLastSeenAt(old);
        revisionRows.put(floodRef, stale);

        // The sweep's two statements, against the same rows (FK: a snapshot keeps its revision).
        when(revisions.findSweepableRefs(any(), any())).thenAnswer(inv -> {
            Instant cutoff = inv.getArgument(0);
            return revisionRows.values().stream()
                    .filter(r -> r.getLastSeenAt().isBefore(cutoff))
                    .filter(r -> snapshotRows.values().stream().noneMatch(s -> s.getRevisionRef().equals(r.getRevisionRef())))
                    .map(AlertSourceRevision::getRevisionRef).toList();
        });
        when(revisions.deleteSweepable(anyCollection(), any())).thenAnswer(inv -> {
            int n = 0;
            for (String ref : (Collection<String>) inv.getArgument(0)) {
                boolean named = snapshotRows.values().stream().anyMatch(s -> s.getRevisionRef().equals(ref));
                if (!named && revisionRows.remove(ref) != null) n++;
            }
            return n;
        });

        // The tick parks inside its lookup, after reading the row as stored.
        java.util.concurrent.CountDownLatch inLookup = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Answer<?> realLookup = lastSeenLookup();
        when(revisions.findLastSeenByRefIn(anyCollection())).thenAnswer(inv -> {
            Object rows = realLookup.answer(inv);
            inLookup.countDown();
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return rows;
        });

        // NWS has been down: the alert rides forward, so the tick does not touch it.
        Snapshot carried = partly(T0, old, Set.of("USGS", "FEMA"), flood());
        Thread tick = new Thread(() -> recorder.onPoll(carried));
        tick.start();
        assertThat(inLookup.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        Thread sweep = new Thread(() -> recorder.sweepRevisionsOnce(30, 1000));
        sweep.start();
        Thread.State sweepWhileTickParked = settle(sweep);

        release.countDown();
        tick.join(5000);
        sweep.join(5000);
        assertThat(tick.isAlive() || sweep.isAlive()).isFalse();

        // The tick finished first: its snapshot now names the revision, so the sweep left it.
        assertThat(revisionRows).as("the revision the tick cached is still stored").containsKey(floodRef);
        assertThat(snapshotRows).as("and its snapshot was written").hasSize(1);
        assertThat(sweepWhileTickParked).as("the sweep waited for the tick").isEqualTo(Thread.State.BLOCKED);

        // And whatever the cache claims, the table has.
        clearInvocations(revisions, snapshots);
        recorder.record(carried);
        assertThat(revisionRows).containsKey(floodRef);
        verifyNoInteractions(revisions, snapshots);
    }

    @Test
    void aSnapshotSweepWaitsForAnInFlightTickToo() throws Exception {
        java.util.concurrent.CountDownLatch inLookup = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(revisions.findLastSeenByRefIn(anyCollection())).thenAnswer(inv -> {
            inLookup.countDown();
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return List.of();
        });
        when(snapshots.findSweepableIds(any(), any())).thenReturn(List.of());

        Thread tick = new Thread(() -> recorder.onPoll(polled(T0, flood())));
        tick.start();
        assertThat(inLookup.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        Thread sweep = new Thread(() -> recorder.sweepSnapshotsOnce(30, 1000));
        sweep.start();
        assertThat(settle(sweep)).as("the sweep waits for the tick").isEqualTo(Thread.State.BLOCKED);
        verify(snapshots, never()).findSweepableIds(any(), any());

        release.countDown();
        tick.join(5000);
        sweep.join(5000);
        verify(snapshots).findSweepableIds(any(), any());
    }

    // ── review 1: the cache-miss lookup reads two columns, not payloads ─

    @Test
    void aCacheMissReadsTheLastSeenProjection_neverTheFullRows() {
        recorder.record(polled(T0, flood(), quake(5.9)));
        clearInvocations(revisions, snapshots);

        newRecorder(64).record(polled(T0, flood(), quake(5.9)));

        verify(revisions, times(1)).findLastSeenByRefIn(anyCollection());
        verify(revisions, never()).findAllById(anyIterable());
        verify(revisions, never()).findById(any());
    }

    // ── review 1: the LRU is ordered by use ─────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void aKeyStillListedEveryTickStaysCached_whileOthersChurnThrough() {
        InterpretationRecorder small = newRecorder(16);   // the floor
        NormalizedAlert keep = quake(5.0);
        small.record(polled(T0, keep));
        String keepRef = AlertSourceFingerprint.revisionRef(keep);
        String keepFp = snapshotRows.values().iterator().next().getFingerprint();

        // More newcomers than the cache holds, with `keep` re-listed on every tick.
        clearInvocations(revisions, snapshots);
        for (int i = 0; i < 20; i++) {
            small.record(polled(T0, keep, TestAlerts.usgs("M x").id("us-other-" + i).magnitude(6.0)
                    .geometry(Map.of("type", "Point", "coordinates", List.of(-118.2, 34.0))).build()));
        }

        for (org.mockito.invocation.Invocation inv : mockingDetails(revisions).getInvocations()) {
            if (inv.getMethod().getName().equals("findLastSeenByRefIn")) {
                assertThat((Collection<String>) inv.getArgument(0)).as("keep's revision is never re-read").doesNotContain(keepRef);
            }
        }
        for (org.mockito.invocation.Invocation inv : mockingDetails(snapshots).getInvocations()) {
            if (inv.getMethod().getName().equals("findIdsByFingerprintIn")) {
                assertThat((Collection<String>) inv.getArgument(0)).as("keep's snapshot is never re-read").doesNotContain(keepFp);
            }
        }

        clearInvocations(revisions, snapshots);
        small.record(polled(T0, keep));
        verifyNoInteractions(revisions, snapshots);
    }

    // ── the in-memory store ─────────────────────────────────────────────

    /** The recorder's cache-miss projection over {@link #revisionRows}. */
    @SuppressWarnings("unchecked")
    private Answer<List<AlertSourceRevisionRepo.LastSeenByRef>> lastSeenLookup() {
        return inv -> {
            List<AlertSourceRevisionRepo.LastSeenByRef> out = new ArrayList<>();
            for (String ref : (Collection<String>) inv.getArgument(0)) {
                AlertSourceRevision r = revisionRows.get(ref);
                if (r == null) continue;
                Instant seen = r.getLastSeenAt();
                out.add(new AlertSourceRevisionRepo.LastSeenByRef() {
                    public String getRevisionRef() { return ref; }
                    public Instant getLastSeenAt() { return seen; }
                });
            }
            return out;
        };
    }

    @SuppressWarnings("unchecked")
    private void fakeStore() {
        when(revisions.findLastSeenByRefIn(anyCollection())).thenAnswer(lastSeenLookup());
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
