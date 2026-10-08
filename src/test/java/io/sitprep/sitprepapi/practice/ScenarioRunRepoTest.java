package io.sitprep.sitprepapi.practice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ScenarioRun} maps on H2: the JSON trace and tags round-trip, the
 * status enum is stored by name, and the finders pick the right rows. The
 * partial unique indexes are Postgres-only (verified on scratch Postgres, EXEC-B).
 */
@DataJpaTest
@ActiveProfiles("test")
class ScenarioRunRepoTest {

    @Autowired
    private ScenarioRunRepo repo;

    @Autowired
    private TestEntityManager em;

    private ScenarioRun run(String hh, String email, ScenarioRun.Status status) {
        ScenarioRun r = new ScenarioRun();
        r.setHouseholdId(hh);
        r.setUserEmail(email);
        r.setScenarioKey("comms-outage-family-reconnect");
        r.setContentVersion(1);
        r.setContentHash("sha256:abc");
        r.setStatus(status);
        r.setLastNodeKey("calls_failing");
        return r;
    }

    @Test
    void traceAndTagsRoundTrip() {
        ScenarioRun r = run(null, "a@example.com", ScenarioRun.Status.COMPLETED);
        r.setDecisionTrace(List.of(new ScenarioRun.TraceStep("calls_failing", "short_text", "2026-10-08T15:00:00Z")));
        r.setDebriefTags(List.of("communicated_clearly"));
        r.setSuggestedActionKey("OPEN_EMERGENCY_CONTACTS");
        r.setCompletedAt(Instant.parse("2026-10-08T15:05:00Z"));
        Long id = repo.save(r).getId();
        em.flush();
        em.clear();

        ScenarioRun back = repo.findById(id).orElseThrow();
        assertThat(back.getDecisionTrace()).containsExactly(
                new ScenarioRun.TraceStep("calls_failing", "short_text", "2026-10-08T15:00:00Z"));
        assertThat(back.getDebriefTags()).containsExactly("communicated_clearly");
        assertThat(back.getStatus()).isEqualTo(ScenarioRun.Status.COMPLETED);
        assertThat(back.getStartedAt()).isNotNull();
        assertThat(back.getCompletedAt()).isEqualTo(Instant.parse("2026-10-08T15:05:00Z"));
    }

    @Test
    void findersSeparateHouseholdAndPersonalRuns() {
        repo.save(run(null, "a@example.com", ScenarioRun.Status.IN_PROGRESS));
        em.flush();

        assertThat(repo.findFirstByUserEmailAndScenarioKeyAndStatusAndHouseholdIdIsNull(
                "a@example.com", "comms-outage-family-reconnect", ScenarioRun.Status.IN_PROGRESS)).isPresent();
        assertThat(repo.findFirstByHouseholdIdAndScenarioKeyAndStatus(
                "hh-1", "comms-outage-family-reconnect", ScenarioRun.Status.IN_PROGRESS)).isEmpty();
        assertThat(repo.findByUserEmailAndHouseholdIdIsNullOrderByStartedAtDesc("a@example.com")).hasSize(1);
    }
}
