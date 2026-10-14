package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S2 C3 — the ingest hook {@link InterpretationRecorder} hangs off: called once
 * per tick in which some source answered, never on a tick where all failed,
 * and never able to fail the tick. Replayed through the real {@code pollAll}
 * against canned bodies; no network.
 */
class AlertIngestPollListenerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-10-14T12:00:00Z");

    private static final String USGS_OK = """
            {"type":"FeatureCollection","features":[{
              "type":"Feature","id":"us7000abc",
              "properties":{"mag":5.1,"place":"10 km N of Somewhere, CA","time":1760356800000},
              "geometry":{"type":"Point","coordinates":[-118.2,34.0,10.0]}
            }]}""";

    private final Set<String> answering = new HashSet<>();
    private AlertIngestService ingest;

    @BeforeEach
    void setUp() {
        NwsZoneService zones = mock(NwsZoneService.class);
        when(zones.zoneCodesForPoint(anyDouble(), anyDouble())).thenReturn(Set.of());
        ingest = new AlertIngestService(zones);
        ingest.setUpstreamForTest((url, accept) -> {
            if (url.contains("usgs.gov") && answering.contains("USGS")) return MAPPER.readTree(USGS_OK);
            throw new RuntimeException("Upstream returned HTTP 503");
        }, Clock.fixed(T0, ZoneOffset.UTC));
    }

    @Test
    void aTickThatHeardFromASourceCallsTheListenerOnceWithTheLiveSnapshot() {
        List<Snapshot> seen = new ArrayList<>();
        ingest.onSuccessfulPoll(seen::add);
        answering.add("USGS");

        ingest.refreshNow();

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0)).isSameAs(ingest.getSnapshot());
        assertThat(seen.get(0).freshSources()).containsExactly("USGS");
    }

    @Test
    void aTickWhereEverySourceFailedRecordsNothing() {
        List<Snapshot> seen = new ArrayList<>();
        ingest.onSuccessfulPoll(seen::add);

        ingest.refreshNow();

        assertThat(seen).isEmpty();
    }

    @Test
    void aThrowingListenerNeverFailsTheTick() {
        List<Snapshot> after = new ArrayList<>();
        ingest.onSuccessfulPoll(s -> { throw new IllegalStateException("db away"); });
        ingest.onSuccessfulPoll(after::add);
        answering.add("USGS");

        ingest.refreshNow();

        assertThat(ingest.getSnapshot().alerts()).hasSize(1);
        assertThat(after).as("later listeners still run").hasSize(1);
    }
}
