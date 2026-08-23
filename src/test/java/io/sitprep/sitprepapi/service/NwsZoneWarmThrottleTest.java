package io.sitprep.sitprepapi.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guards for P1-11 — zone warming reintroducing the unthrottled
 * upstream loop the audit flagged for Nominatim (P1-9).
 *
 * <h2>Why this file exists</h2>
 *
 * <p>The bug was found by <b>reading a log line</b> during a full test run
 * — {@code NwsZone: warming 909 zone centroid(s)} — which is an observation,
 * not a control. Nothing would have stopped the next edit reintroducing it,
 * and the failure mode is silent: ~3 minutes of continuous requests to
 * api.weather.gov on every dyno restart and every {@code mvn package}, showing
 * up only as a rate-limit block at the worst possible moment.</p>
 *
 * <p>So the throttle decision was extracted into a pure function and these
 * assert it directly.</p>
 */
class NwsZoneWarmThrottleTest {

    private static List<String> codes(String prefix, int n) {
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(String.format("%s%03d", prefix, i));
        return out;
    }

    // ------------------------------------------------------------------
    // The cap
    // ------------------------------------------------------------------

    @Test
    void queueIsCappedPerTick() {
        Set<String> attempted = ConcurrentHashMap.newKeySet();
        List<String> selected =
                NwsZoneService.selectForWarm(codes("AZZ", 909), Set.of(), attempted, 100);

        assertThat(selected)
                .as("909 candidates must not become 909 upstream requests in one tick")
                .hasSize(100);
    }

    @Test
    void deferredCodesAreLeftUnmarkedSoTheNextTickRetriesThem() {
        Set<String> attempted = ConcurrentHashMap.newKeySet();

        List<String> first = NwsZoneService.selectForWarm(codes("AZZ", 250), Set.of(), attempted, 100);
        assertThat(first).hasSize(100);
        assertThat(attempted)
                .as("only the queued 100 should be marked; marking the deferred 150 "
                        + "would turn a truncation into a permanent drop")
                .hasSize(100);

        List<String> second = NwsZoneService.selectForWarm(codes("AZZ", 250), Set.of(), attempted, 100);
        assertThat(second).hasSize(100);
        assertThat(second).doesNotContainAnyElementsOf(first);

        List<String> third = NwsZoneService.selectForWarm(codes("AZZ", 250), Set.of(), attempted, 100);
        assertThat(third).as("the remaining 50").hasSize(50);

        List<String> fourth = NwsZoneService.selectForWarm(codes("AZZ", 250), Set.of(), attempted, 100);
        assertThat(fourth).as("steady state queues nothing").isEmpty();
    }

    @Test
    void theWholeSetIsEventuallyCoveredAcrossTicks() {
        Set<String> attempted = ConcurrentHashMap.newKeySet();
        Set<String> seen = new HashSet<>();
        for (int tick = 0; tick < 10; tick++) {
            seen.addAll(NwsZoneService.selectForWarm(codes("AZZ", 254), Set.of(), attempted, 100));
        }
        assertThat(seen).as("throttling delays work, it must not lose it").hasSize(254);
    }

    // ------------------------------------------------------------------
    // Not re-fetching what we already have
    // ------------------------------------------------------------------

    @Test
    void alreadyCachedCodesAreNeverRequeued() {
        Set<String> known = Set.of("AZZ000", "AZZ001", "AZZ002");
        Set<String> attempted = ConcurrentHashMap.newKeySet();

        assertThat(NwsZoneService.selectForWarm(codes("AZZ", 5), known, attempted, 100))
                .containsExactly("AZZ003", "AZZ004");
    }

    @Test
    void duplicatesInOneTickCostOneRequest() {
        Set<String> attempted = ConcurrentHashMap.newKeySet();
        // An alert list where many alerts target the same zone — the common
        // case, and the reason warming one zone per alert is not enough on its
        // own to bound the work.
        List<String> dupes = List.of("AZZ560", "AZZ560", "azz560", " AZZ560 ", "ORZ691");

        assertThat(NwsZoneService.selectForWarm(dupes, Set.of(), attempted, 100))
                .containsExactly("AZZ560", "ORZ691");
    }

    @Test
    void nullsAndBlanksAreIgnoredRatherThanRequested() {
        Set<String> attempted = ConcurrentHashMap.newKeySet();
        List<String> messy = new ArrayList<>(List.of("AZZ560", "   ", "ORZ691"));
        messy.add(null);

        assertThat(NwsZoneService.selectForWarm(messy, Set.of(), attempted, 100))
                .containsExactly("AZZ560", "ORZ691");
    }

    @Test
    void emptyAndDegenerateInputsQueueNothing() {
        Set<String> attempted = ConcurrentHashMap.newKeySet();
        assertThat(NwsZoneService.selectForWarm(null, Set.of(), attempted, 100)).isEmpty();
        assertThat(NwsZoneService.selectForWarm(List.of(), Set.of(), attempted, 100)).isEmpty();
        // A zero or negative cap must mean "queue nothing", not "queue all".
        assertThat(NwsZoneService.selectForWarm(codes("AZZ", 10), Set.of(), attempted, 0)).isEmpty();
        assertThat(NwsZoneService.selectForWarm(codes("AZZ", 10), Set.of(), attempted, -1)).isEmpty();
    }

    // ------------------------------------------------------------------
    // The constants themselves
    // ------------------------------------------------------------------

    @Test
    void theTickCapStaysInAReasonableBand() {
        // Guards the "just bump it to 10000" edit. At 250 ms spacing, 100 per
        // 5-minute tick is ~25 s of upstream work per tick — comfortably
        // inside the window and polite. A cap above ~200 would start
        // overlapping the next tick.
        assertThat(NwsZoneService.maxWarmPerTick()).isBetween(20, 200);
    }

    @Test
    void warmFetchesStaySpaced() {
        // Zero spacing is the 909-burst shape again, just with a smaller
        // number in front of it.
        assertThat(NwsZoneService.warmSpacingMs()).isBetween(100L, 2_000L);
    }

    @Test
    void aFullNationalFeedFitsInsideOneTickWindow() {
        // The real guard: worst-case cold start must not exceed the 5-minute
        // ingest cadence, or ticks pile up on a single-threaded executor.
        long worstCaseMs = (long) NwsZoneService.maxWarmPerTick() * NwsZoneService.warmSpacingMs();
        assertThat(worstCaseMs)
                .as("one tick's warm burst (%d fetches x %d ms) must finish well "
                                + "inside the 5-minute ingest interval",
                        NwsZoneService.maxWarmPerTick(), NwsZoneService.warmSpacingMs())
                .isLessThan(300_000L / 2);
    }
}
