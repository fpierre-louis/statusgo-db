package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.AlertHistory;
import io.sitprep.sitprepapi.repo.AlertHistoryRepo;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The SCHEDULED history tick revises a stored USGS row on real JPA (H2).
 *
 * <p>Prod 2026-10-15 (BE 7f9110b): every tick failed with
 * {@code LazyInitializationException} on {@code AlertHistory.tokens}. The
 * scheduler calls {@code scheduledRecord()}, which calls {@code record()} on
 * {@code this} — a self-call, so {@code record}'s {@code @Transactional} never
 * applied, and C1's reindex of a revised USGS/FEMA row touched the lazy token
 * collection with no session. The unit tests mock the repository, so nothing
 * was ever lazy there. This test goes through the Spring proxy, as the
 * scheduler does, with no test transaction around it.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@TestPropertySource(properties = "alerts.history.enabled=true")
@Import(AlertHistoryScheduledTickTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AlertHistoryScheduledTickTest {

    static final AlertIngestService INGEST = mock(AlertIngestService.class);

    @TestConfiguration
    static class Config {
        @Bean
        AlertHistoryService alertHistoryService(AlertHistoryRepo repo) {
            return new AlertHistoryService(repo, INGEST, null, null);
        }
    }

    @Autowired private AlertHistoryService history;
    @Autowired private AlertHistoryRepo repo;
    @Autowired private TransactionTemplate tx;

    @Test
    void theScheduledTickRevisesAStoredUsgsRow() {
        NormalizedAlert first = TestAlerts.usgs("M 5.9 - 10 km N of Somewhere").id("us-sched-1")
                .magnitude(5.9).ugc(List.of("AZC013")).build();
        NormalizedAlert revised = TestAlerts.usgs("M 6.2 - 10 km N of Somewhere").id("us-sched-1")
                .magnitude(6.2).ugc(List.of("AZC013", "AZC021")).build();

        Instant t0 = Instant.now();
        when(INGEST.getSnapshot()).thenReturn(new Snapshot(List.of(first), t0, t0));
        history.scheduledRecord();
        assertThat(repo.findById("us-sched-1")).isPresent();

        Instant t1 = t0.plusSeconds(300);
        when(INGEST.getSnapshot()).thenReturn(new Snapshot(List.of(revised), t1, t1));
        history.scheduledRecord();

        AlertHistory row = tx.execute(s -> {
            AlertHistory r = repo.findById("us-sched-1").orElseThrow();
            r.getTokens().size();   // initialise inside the session
            return r;
        });
        assertThat(row.getPayload()).contains("6.2");
        assertThat(row.getTokens()).contains("AZC021");
        assertThat(row.getLastSeenAt()).isAfterOrEqualTo(t0);
    }
}
