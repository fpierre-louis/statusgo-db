package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.constant.LocationFreshness;
import io.sitprep.sitprepapi.domain.AlertHistory;
import io.sitprep.sitprepapi.dto.AlertFeedResponse;
import io.sitprep.sitprepapi.dto.AlertHistoryResponse;
import io.sitprep.sitprepapi.repo.AlertHistoryRepo;
import io.sitprep.sitprepapi.resource.AppConfigResource;
import io.sitprep.sitprepapi.service.AlertIngestService.MatchType;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contract tests for recorded alert history.
 *
 * <p>The feature exists because history cannot be fetched — measured
 * 2026-09-07, the NWS archive retains roughly five days and silently ignores an
 * older {@code start}. Everything below is a property that has to hold for a
 * recording to be worth more than the thing it replaces.</p>
 *
 * <p>The fixture is the same 2026-08-22 live capture the rest of the alert
 * epic measures against, so these assertions run against real wire shapes
 * rather than a hand-written approximation of them.</p>
 */
class AlertHistoryServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** San Carlos AZ — the zone the live Extreme Heat Warning targets. */
    private static final Set<String> SAN_CARLOS = Set.of("AZZ560", "AZC007", "AZZ133");
    private static final double LAT = 33.3172, LNG = -110.5297;

    private static AlertIngestService ingest;
    private static AlertDispatchService dispatch;
    private static AlertFeedService feed;
    private static List<NormalizedAlert> liveFeed;

    private Map<String, AlertHistory> table;
    private AlertHistoryRepo repo;
    private AlertHistoryService history;

    @BeforeAll
    static void loadFixture() throws Exception {
        NwsZoneService zones = new NwsZoneService();
        zones.seedPointZones(LAT, LNG, SAN_CARLOS);

        ingest = new AlertIngestService(zones);
        dispatch = new AlertDispatchService(null, null, null, null, null, null, zones, null);
        dispatch.loadTemplates();
        feed = new AlertFeedService(ingest, dispatch);

        try (InputStream in = AlertHistoryServiceTest.class
                .getResourceAsStream("/fixtures/nws-active-2026-08-22.json")) {
            JsonNode root = MAPPER.readTree(in);
            liveFeed = ingest.parseNwsFeed(root);
            ingest.setSnapshotForTest(liveFeed);
        }
    }

    @BeforeEach
    void freshTable() {
        table = new LinkedHashMap<>();
        repo = inMemoryRepo();
        history = new AlertHistoryService(repo, ingest, dispatch, feed);
    }

    // ------------------------------------------------------------------
    // Dedup — the property the whole table shape exists for
    // ------------------------------------------------------------------

    @Test
    void aRepeatedTickWritesNoNewRows() {
        Snapshot first = new Snapshot(liveFeed, Instant.now(), Instant.now());
        int inserted = history.record(first);
        assertThat(inserted).isGreaterThan(0);
        int afterFirst = table.size();

        // A LATER generation carrying the SAME alerts. This is the real case:
        // a multi-day Extreme Heat Warning is in every snapshot for days.
        Snapshot second = new Snapshot(liveFeed, Instant.now().plusSeconds(150), Instant.now());
        assertThat(history.record(second)).isZero();
        assertThat(table).hasSize(afterFirst);
    }

    @Test
    void aRepeatedSightingMovesLastSeenAndLeavesFirstSeenAlone() {
        Instant t0 = Instant.now();
        history.record(new Snapshot(liveFeed, t0, t0));
        AlertHistory row = table.values().iterator().next();
        Instant firstSeen = row.getFirstSeenAt();
        Instant seenOnce = row.getLastSeenAt();

        history.record(new Snapshot(liveFeed, t0.plusSeconds(300), t0));

        AlertHistory again = table.get(row.getAlertId());
        assertThat(again.getFirstSeenAt())
                .as("when the alert first appeared is a fact about the alert")
                .isEqualTo(firstSeen);
        assertThat(again.getLastSeenAt())
                .as("still in force — that is the whole update")
                .isAfterOrEqualTo(seenOnce);
    }

    @Test
    void resamplingTheSameSnapshotGenerationIsFree() {
        Instant gen = Instant.now();
        Snapshot snap = new Snapshot(liveFeed, gen, gen);
        history.record(snap);
        int rows = table.size();

        // The history tick runs at half the ingest cadence, so most ticks see a
        // generation already recorded. Those must cost nothing, not merely be
        // idempotent.
        assertThat(history.record(snap)).isZero();
        assertThat(table).hasSize(rows);
    }

    @Test
    void theFixtureReallyDoesContainRepeatedProducts() {
        // Guards the dedup tests above: they would pass trivially against a
        // feed of 310 unique one-off alerts. Oklahoma City's month of history
        // is 23 Heat Advisories, and the naive one-row-per-poll design turns
        // that into hundreds of rows each.
        long heat = liveFeed.stream()
                .filter(a -> "Heat Advisory".equals(a.event()) || "Extreme Heat Warning".equals(a.event()))
                .count();
        assertThat(heat).isGreaterThan(1);
    }

    // ------------------------------------------------------------------
    // The prefilter must never drop a row the real matcher would accept
    // ------------------------------------------------------------------

    @Test
    void everyAlertMatchTypeForAcceptsSurvivesThePrefilter() {
        // THE load-bearing test. The candidate query narrows ~45,000 rows to a
        // few hundred so the payloads can be deserialized at all; if that
        // narrowing is not a strict superset of what matchTypeFor accepts, the
        // real matcher never gets asked about the row it would have matched and
        // the alert silently vanishes from history.
        int radiusMi = AppConfigResource.alertsRadiusMi();
        double radiusKm = radiusMi * 1.609344;
        Set<String> zones = ingest.zoneCodesForPoint(LAT, LNG);
        Set<String> states = AlertIngestService.statePrefixesOf(zones);
        Collection<String> tokens = AlertHistoryService.candidateTokens(zones, states);

        int matched = 0;
        for (NormalizedAlert a : liveFeed) {
            if (AlertIngestService.matchTypeFor(a, LAT, LNG, radiusKm, zones, states) == null) {
                continue;
            }
            matched++;
            AlertHistory row = history.toRow(a, Instant.now());
            assertThat(passesPrefilter(row, tokens, LAT, LNG, radiusKm))
                    .as("prefilter dropped %s (%s), which matchTypeFor accepts", a.id(), a.event())
                    .isTrue();
        }
        assertThat(matched)
                .as("the fixture must actually match something here or this test proves nothing")
                .isGreaterThan(0);
    }

    @Test
    void thePrefilterStillNarrows() {
        // A prefilter that keeps everything is safe and useless. This pins that
        // it is doing real work — otherwise the superset test above could be
        // satisfied by `WHERE true`.
        double radiusKm = AppConfigResource.alertsRadiusMi() * 1.609344;
        Set<String> zones = ingest.zoneCodesForPoint(LAT, LNG);
        Set<String> states = AlertIngestService.statePrefixesOf(zones);
        Collection<String> tokens = AlertHistoryService.candidateTokens(zones, states);

        long kept = liveFeed.stream()
                .map(a -> history.toRow(a, Instant.now()))
                .filter(r -> passesPrefilter(r, tokens, LAT, LNG, radiusKm))
                .count();

        assertThat(kept).isLessThan(liveFeed.size() / 2);
    }

    @Test
    void anAlertWithNeitherGeometryNorZonesIsNeverExcludable() {
        // matchTypeFor returns BROADCAST for such an alert at every coordinate
        // — that is the FEMA case, whose rows carry county and state NAMES but
        // never codes. A prefilter that dropped it would hide every federal
        // declaration from history.
        NormalizedAlert bare = TestAlerts.fema("Major disaster declared")
                .id("fema-1").ugc(List.of()).geometry(null).build();
        AlertHistory row = history.toRow(bare, Instant.now());

        assertThat(row.isBroadcast()).isTrue();
        assertThat(passesPrefilter(row, List.of("ZZZ999"), 0, 0, 1)).isTrue();
    }

    @Test
    void candidateTokensFollowTheSameLadderPrecedenceAsTheMatcher() {
        // matchTypeFor falls back to state prefixes ONLY when the point's zones
        // are unknown; with zones in hand an alert targeting none of them is a
        // definite no. Passing state tokens anyway would drag every alert in
        // the state into the candidate set for nothing.
        assertThat(AlertHistoryService.candidateTokens(Set.of("AZZ560"), Set.of("AZ")))
                .containsExactly("AZZ560");
        assertThat(AlertHistoryService.candidateTokens(Set.of(), Set.of("AZ")))
                .containsExactly("AZ");
        assertThat(AlertHistoryService.candidateTokens(Set.of(), Set.of()))
                .as("an empty IN list is not legal SQL")
                .isNotEmpty();
    }

    @Test
    void tokensCarryBothTheZoneCodeAndItsStatePrefix() {
        NormalizedAlert a = TestAlerts.nws("Flood Warning")
                .ugc(List.of("azz560", "ORZ691")).build();
        assertThat(AlertHistoryService.tokensFor(a))
                .containsExactlyInAnyOrder("AZZ560", "AZ", "ORZ691", "OR");
    }

    @Test
    void theBoundingBoxCircumscribesTheRadiusRatherThanInscribingIt() {
        // Wider than the circle is the safe direction: the box only decides
        // which rows get asked, matchTypeFor decides which ones answer yes.
        double radiusKm = 50;
        double northEdgeKm = (AlertHistoryService.latMax(LAT, radiusKm) - LAT) * 111.32;
        assertThat(northEdgeKm).isGreaterThanOrEqualTo(radiusKm);

        // Longitude degrees shrink with latitude, so the box must widen there.
        double atEquator = AlertHistoryService.lngMax(0, 0, radiusKm);
        double atSixty = AlertHistoryService.lngMax(60, 0, radiusKm);
        assertThat(atSixty).isGreaterThan(atEquator);
    }

    // ------------------------------------------------------------------
    // Payload fidelity — history renders through the live pipeline
    // ------------------------------------------------------------------

    @Test
    void aStoredAlertRehydratesIntoSomethingTheMatcherTreatsIdentically() {
        double radiusKm = AppConfigResource.alertsRadiusMi() * 1.609344;
        Set<String> zones = ingest.zoneCodesForPoint(LAT, LNG);
        Set<String> states = AlertIngestService.statePrefixesOf(zones);

        int checked = 0;
        for (NormalizedAlert original : liveFeed) {
            MatchType before = AlertIngestService.matchTypeFor(original, LAT, LNG, radiusKm, zones, states);
            NormalizedAlert restored = history.rehydrate(history.toRow(original, Instant.now()));
            assertThat(restored).as("payload for %s did not read back", original.id()).isNotNull();

            MatchType after = AlertIngestService.matchTypeFor(restored, LAT, LNG, radiusKm, zones, states);
            assertThat(after)
                    .as("a round-trip changed whether %s applies at this point", original.id())
                    .isEqualTo(before);
            checked++;
        }
        assertThat(checked).isEqualTo(liveFeed.size());
    }

    @Test
    void historyCardsComeFromTheTemplatePipelineAndNeverFromWireText() {
        // Most history rows are advisory-tier, which is the tier with the
        // thinnest template coverage. That is an argument for closing the
        // template gap, NOT for letting history print raw NWS prose as a
        // workaround — every rule P0-1 through P0-5 established applies here.
        seedWholeFeed();
        AlertHistoryResponse res = history.historyFor(LAT, LNG, 30);
        assertThat(res.alerts()).isNotEmpty();

        for (var card : res.alerts()) {
            NormalizedAlert wire = liveFeed.stream()
                    .filter(a -> a.id().equals(card.id())).findFirst().orElseThrow();
            if (card.headline() != null && wire.headline() != null) {
                assertThat(card.headline())
                        .as("card headline for %s is the raw wire headline", card.id())
                        .isNotEqualTo(wire.headline());
            }
        }
    }

    @Test
    void historyAndTheLiveFeedAgreeOnWhatNearMeMeans() {
        // Two matching systems is how they drift, so this pins that the
        // recorded path reaches the same verdict as matchTypeFor — the method
        // /alerts/feed uses — for the same coordinate.
        //
        // NOT an equality check against the live feed's ids. The two apply
        // different LIFECYCLE filters on purpose: the live feed drops what has
        // ended, and what has ended is the entire subject of history. Asserting
        // they return the same list would be asserting the feature does not
        // work.
        seedWholeFeed();
        double radiusKm = AppConfigResource.alertsRadiusMi() * 1.609344;
        Set<String> zones = ingest.zoneCodesForPoint(LAT, LNG);
        Set<String> states = AlertIngestService.statePrefixesOf(zones);

        List<String> fromHistory = history.historyFor(LAT, LNG, 30).alerts()
                .stream().map(c -> c.id()).sorted().toList();

        List<String> theMatcherAccepts = liveFeed.stream()
                .filter(a -> AlertSafetyPolicy.publishabilityBlockReason(a) == null)
                .filter(a -> AlertIngestService.matchTypeFor(a, LAT, LNG, radiusKm, zones, states) != null)
                .map(NormalizedAlert::id)
                .sorted().toList();

        assertThat(fromHistory).isNotEmpty().isEqualTo(theMatcherAccepts);
    }

    @Test
    void theLiveFeedIsAlwaysASubsetOfHistory() {
        // Whatever is live right now was, a moment ago, also recorded. If an
        // alert can be on the live feed and absent from history for the same
        // point, the recorder is dropping something it saw.
        seedWholeFeed();

        List<String> fromHistory = history.historyFor(LAT, LNG, 30).alerts()
                .stream().map(c -> c.id()).toList();
        List<String> live = feed.feedFor(LAT, LNG).alerts()
                .stream().map(c -> c.id()).toList();

        assertThat(fromHistory).containsAll(live);
    }

    @Test
    void anEndedAlertKeepsItsRecordButLosesItsInstructions() {
        // The safety rule, held on the side of the wire that owns it. An ended
        // alert's present-tense "what to do" must not render as current — that
        // is why the frontend refuses to expand an ended row, and the backend
        // should not be handing it the text to expand in the first place.
        //
        // The fixture is a 2026-08-22 capture, so every alert in it has ended.
        seedWholeFeed();
        var cards = history.historyFor(LAT, LNG, 30).alerts();
        assertThat(cards).isNotEmpty();

        assertThat(cards).allSatisfy(card -> {
            assertThat(card.whatToDo())
                    .as("%s is over; its instructions are not current advice", card.id())
                    .isNull();
            assertThat(card.precautions())
                    .as("%s is over; its precautions are not current advice", card.id())
                    .isNull();
        });
    }

    @Test
    void aRetractionIsNotAnEventAndNeverBecomesAHistoryRow() {
        // A Cancel is not a thing that happened; it is a retraction of a thing
        // that happened, and that thing already has its own row. Listing both
        // would double-count the event — and P0-8 is the standing reminder of
        // what happens when cancellation copy gets treated as alert copy.
        NormalizedAlert cancel = TestAlerts.nws("Extreme Heat Warning")
                .id("cancel-1").messageType("Cancel").response("AllClear")
                .endsAt("2099-01-01T00:00:00Z").build();
        NormalizedAlert drill = TestAlerts.nws("Tornado Warning")
                .id("drill-1").status("Test").endsAt("2099-01-01T00:00:00Z").build();
        NormalizedAlert real = TestAlerts.nws("Flood Warning")
                .id("real-1").endsAt("2099-01-01T00:00:00Z").build();

        history.record(new Snapshot(List.of(cancel, drill, real), Instant.now(), Instant.now()));

        assertThat(history.historyFor(LAT, LNG, 30).alerts())
                .extracting(c -> c.id())
                .containsExactly("real-1");
    }

    // ------------------------------------------------------------------
    // Window, retention, and the empty state
    // ------------------------------------------------------------------

    @Test
    void aCallerCannotAskForMoreHistoryThanWeKeep() {
        // A window wider than retention would answer with a silence it created
        // itself — "nothing in 90 days" over a table swept at 30.
        assertThat(history.clampDays(90)).isEqualTo(history.retentionDays());
        assertThat(history.clampDays(7)).isEqualTo(7);
        assertThat(history.clampDays(0)).isEqualTo(history.retentionDays());
        assertThat(history.clampDays(-5)).isEqualTo(history.retentionDays());
    }

    @Test
    void theWindowActuallyAppliedIsReportedBack() {
        seedWholeFeed();
        assertThat(history.historyFor(LAT, LNG, 90).meta().days())
                .isEqualTo(history.retentionDays());
        assertThat(history.historyFor(LAT, LNG, 7).meta().days()).isEqualTo(7);
    }

    @Test
    void theEmptyStateCanSayWhenRecordingBegan() {
        // "Nothing happened here in 30 days" and "we started watching on
        // Tuesday" are different claims, and on Wednesday only one is true.
        // Quiet is the NORMAL case — Lehi UT had one alert in thirty days — so
        // the surface rendering nothing has to be able to say why.
        Instant began = Instant.now().minus(3, ChronoUnit.DAYS);
        history.record(new Snapshot(liveFeed, began, began));
        table.values().forEach(r -> r.setFirstSeenAt(began));

        // The middle of the Pacific: nothing in the fixture matches here.
        AlertHistoryResponse quiet = history.historyFor(0.0, -160.0, 30);
        assertThat(quiet.alerts()).isEmpty();
        assertThat(quiet.meta().recordingSince()).isEqualTo(began.toString());
    }

    @Test
    void recordingSinceIsNullBeforeAnythingHasEverBeenRecorded() {
        AlertHistoryResponse res = history.historyFor(LAT, LNG, 30);
        assertThat(res.alerts()).isEmpty();
        assertThat(res.meta().recordingSince()).isNull();
    }

    @Test
    void theCoverageCaveatIsTheSameStringTheLiveFeedShips() {
        // One string, one place. A gap in coverage is a gap in the history too,
        // and a second copy of the sentence is a second thing to forget to
        // update.
        seedWholeFeed();
        assertThat(history.historyFor(LAT, LNG, 30).meta().coverageCaveat())
                .isEqualTo(AlertFeedResponse.COVERAGE_CAVEAT)
                .isEqualTo(feed.feedFor(LAT, LNG).meta().coverageCaveat());
    }

    @Test
    void theSweepReapsPastTheRetentionWindow() {
        // This table must not become the third never-reaped one alongside Post
        // and AlertPost. At ~1,510 new alerts a day nationally that is not a
        // tidiness question.
        history.record(new Snapshot(liveFeed, Instant.now(), Instant.now()));
        int total = table.size();

        Instant stale = Instant.now().minus(history.retentionDays() + 1L, ChronoUnit.DAYS);
        List<AlertHistory> rows = new ArrayList<>(table.values());
        rows.subList(0, 5).forEach(r -> r.setLastSeenAt(stale));

        assertThat(history.sweepOnce(1000)).isEqualTo(5);
        assertThat(table).hasSize(total - 5);
    }

    @Test
    void theSweepLeavesRowsInsideTheWindowAlone() {
        history.record(new Snapshot(liveFeed, Instant.now(), Instant.now()));
        int total = table.size();
        assertThat(history.sweepOnce(1000)).isZero();
        assertThat(table).hasSize(total);
    }

    @Test
    void aSweepBatchIsBounded() {
        // One tick, one batch — successive ticks drain a backlog rather than
        // one tick holding a transaction open over the whole table.
        history.record(new Snapshot(liveFeed, Instant.now(), Instant.now()));
        Instant stale = Instant.now().minus(history.retentionDays() + 1L, ChronoUnit.DAYS);
        table.values().forEach(r -> r.setLastSeenAt(stale));

        assertThat(history.sweepOnce(10)).isEqualTo(10);
        assertThat(table).isNotEmpty();
    }

    // ------------------------------------------------------------------
    // Location freshness — F-5 extended to history (audit R-5)
    // ------------------------------------------------------------------

    @Test
    void historyCarriesTheSameLocationVerdictTheLiveFeedDoes() {
        // The "also resolve" item. History takes lat/lng exactly the way the
        // live feed does, so a stale coordinate produces a confident answer
        // about the wrong place — and here that answer is "nothing has been
        // active near you in the last 30 days", which reads as a settled record
        // rather than one snapshot. It is the sentence a user standing
        // somewhere else would be most reassured and most wrong to believe.
        seedWholeFeed();
        Instant threeWeeksAgo = Instant.now().minus(21, ChronoUnit.DAYS);

        var meta = history.historyFor(LAT, LNG, 30, threeWeeksAgo).meta();
        assertThat(meta.locationAge()).isNotNull();
        assertThat(meta.locationAge().isStale()).isTrue();
        assertThat(meta.locationAge().maxAgeDays())
                .isEqualTo(LocationFreshness.maxAgeDays());
    }

    @Test
    void historyReportsUnknownWhenTheCallerSendsNoFixTimestamp() {
        seedWholeFeed();
        assertThat(history.historyFor(LAT, LNG, 30).meta().locationAge()).isNull();
    }

    @Test
    void aFreshFixLeavesTheHistoryVerdictClean() {
        seedWholeFeed();
        var meta = history.historyFor(LAT, LNG, 30, Instant.now().minus(2, ChronoUnit.MINUTES)).meta();
        assertThat(meta.locationAge()).isNotNull();
        assertThat(meta.locationAge().isStale()).isFalse();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void seedWholeFeed() {
        history.record(new Snapshot(liveFeed, Instant.now(), Instant.now()));
    }

    /**
     * The candidate query's WHERE clause, in Java.
     *
     * <p>Deliberately a transcription rather than a call into the repo: these
     * tests run without a database, and the point of the superset test is to
     * pin the <i>predicate</i>, which is what a future edit to the JPQL would
     * change. Keep the two in step — if the query gains a disjunct, so does
     * this.</p>
     */
    private static boolean passesPrefilter(AlertHistory row,
                                           Collection<String> tokens,
                                           double lat, double lng, double radiusKm) {
        if (row.isBroadcast()) return true;
        if (row.getCentroidLat() != null
                && row.getCentroidLat() >= AlertHistoryService.latMin(lat, radiusKm)
                && row.getCentroidLat() <= AlertHistoryService.latMax(lat, radiusKm)
                && row.getCentroidLng() >= AlertHistoryService.lngMin(lat, lng, radiusKm)
                && row.getCentroidLng() <= AlertHistoryService.lngMax(lat, lng, radiusKm)) {
            return true;
        }
        return row.getTokens().stream().anyMatch(tokens::contains);
    }

    /** A HashMap wearing the repo interface — real upsert semantics, no database. */
    private AlertHistoryRepo inMemoryRepo() {
        AlertHistoryRepo r = mock(AlertHistoryRepo.class);

        when(r.findAllById(any())).thenAnswer(inv -> {
            Iterable<String> ids = inv.getArgument(0);
            List<AlertHistory> out = new ArrayList<>();
            for (String id : ids) {
                AlertHistory row = table.get(id);
                if (row != null) out.add(row);
            }
            return out;
        });

        when(r.saveAll(any())).thenAnswer(inv -> {
            Iterable<AlertHistory> rows = inv.getArgument(0);
            List<AlertHistory> out = new ArrayList<>();
            for (AlertHistory row : rows) {
                table.put(row.getAlertId(), row);
                out.add(row);
            }
            return out;
        });

        when(r.findCandidates(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), any()))
                .thenAnswer(inv -> {
                    Instant since = inv.getArgument(0);
                    double latMin = inv.getArgument(1), latMax = inv.getArgument(2);
                    double lngMin = inv.getArgument(3), lngMax = inv.getArgument(4);
                    Collection<String> tokens = inv.getArgument(5);
                    return table.values().stream()
                            .filter(row -> row.getLastSeenAt().isAfter(since))
                            .filter(row -> row.isBroadcast()
                                    || (row.getCentroidLat() != null
                                        && row.getCentroidLat() >= latMin && row.getCentroidLat() <= latMax
                                        && row.getCentroidLng() >= lngMin && row.getCentroidLng() <= lngMax)
                                    || row.getTokens().stream().anyMatch(tokens::contains))
                            .toList();
                });

        when(r.findRecordingSince()).thenAnswer(inv -> table.values().stream()
                .map(AlertHistory::getFirstSeenAt)
                .min(Instant::compareTo)
                .orElse(null));

        when(r.findIdsOlderThan(any(), any())).thenAnswer(inv -> {
            Instant cutoff = inv.getArgument(0);
            org.springframework.data.domain.Pageable page = inv.getArgument(1);
            return table.values().stream()
                    .filter(row -> row.getLastSeenAt().isBefore(cutoff))
                    .limit(page.getPageSize())
                    .map(AlertHistory::getAlertId)
                    .toList();
        });

        org.mockito.Mockito.doAnswer(inv -> {
            Iterable<String> ids = inv.getArgument(0);
            for (String id : ids) table.remove(id);
            return null;
        }).when(r).deleteAllById(any());

        return r;
    }
}
