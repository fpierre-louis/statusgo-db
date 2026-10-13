package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.AlertHistory;
import io.sitprep.sitprepapi.repo.AlertHistoryRepo;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code lastSeenAt} means "upstream listed it", not "we still had it in
 * memory" (Phase 3 D5).
 *
 * <p>A failed source's alerts ride forward in the snapshot so the feed keeps
 * serving them. Recording bumped {@code lastSeenAt} on all of them anyway, so
 * an outage looked like a run of confirmed sightings — and history could never
 * tell "still listed" from "we stopped being able to ask".</p>
 */
class AlertHistoryLastSeenTest {

    private static final Instant LONG_AGO = Instant.parse("2026-10-01T00:00:00Z");

    private final Map<String, AlertHistory> table = new LinkedHashMap<>();
    private AlertHistoryService history;

    private final NormalizedAlert tornado = TestAlerts.nws("Tornado Warning").id("nws-1").build();
    private final NormalizedAlert quake = TestAlerts.usgs("M 5.1 - somewhere").id("us-1").build();

    @BeforeEach
    void setUp() {
        AlertHistoryRepo repo = mock(AlertHistoryRepo.class);
        when(repo.findAllById(any())).thenAnswer(inv -> {
            List<AlertHistory> out = new ArrayList<>();
            for (String id : (Iterable<String>) inv.getArgument(0)) {
                if (table.containsKey(id)) out.add(table.get(id));
            }
            return out;
        });
        when(repo.saveAll(any())).thenAnswer(inv -> {
            List<AlertHistory> out = new ArrayList<>();
            for (AlertHistory row : (Iterable<AlertHistory>) inv.getArgument(0)) {
                table.put(row.getAlertId(), row);
                out.add(row);
            }
            return out;
        });
        history = new AlertHistoryService(repo, null, null, null);

        // Both rows exist already, last seen long ago.
        Instant t = Instant.now();
        history.record(new Snapshot(List.of(tornado, quake), t, t));
        table.values().forEach(r -> r.setLastSeenAt(LONG_AGO));
    }

    private void tick(Set<String> fresh) {
        Instant t = Instant.now().plusSeconds(300);
        history.record(new Snapshot(List.of(tornado, quake), t, t,
                Map.of("NWS", LONG_AGO, "USGS", t, "FEMA", t), fresh));
    }

    @Test
    void aCarriedForwardAlertKeepsItsLastSeen() {
        tick(Set.of("USGS", "FEMA"));   // NWS failed; its alert rode forward

        assertThat(table.get("nws-1").getLastSeenAt())
                .as("NWS was not asked this tick, so nobody saw the warning")
                .isEqualTo(LONG_AGO);
        assertThat(table.get("us-1").getLastSeenAt())
                .as("USGS answered and listed the quake")
                .isAfter(LONG_AGO);
    }

    @Test
    void aFreshlyPolledAlertIsStamped() {
        tick(Set.of("NWS", "USGS", "FEMA"));

        assertThat(table.get("nws-1").getLastSeenAt()).isAfter(LONG_AGO);
        assertThat(table.get("us-1").getLastSeenAt()).isAfter(LONG_AGO);
    }

    @Test
    void aTickWithNoFreshSourceStampsNothing() {
        tick(Set.of());

        assertThat(table.values()).allSatisfy(r -> assertThat(r.getLastSeenAt()).isEqualTo(LONG_AGO));
    }
}
