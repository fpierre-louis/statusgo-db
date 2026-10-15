package io.sitprep.sitprepapi.repo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

import static io.sitprep.sitprepapi.repo.AlertSourceRevisionThreadQueryTest.refs;
import static io.sitprep.sitprepapi.repo.AlertSourceRevisionThreadQueryTest.seedRows;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2 C4 — the batched thread lookup ({@link AlertSourceRevisionThreadQuery}) on
 * REAL Postgres over the replayed Flyway schema through V110: the same seed
 * and the same questions as {@link AlertSourceRevisionThreadQueryTest} on H2.
 * What H2 cannot vouch for is Postgres's own reading of
 * {@code concat(' ', col, ' ') LIKE … ESCAPE '!'} — a NULL column, LIKE
 * wildcards inside an id, whole-token bounds.
 *
 * <p><b>Runs only when {@code ALERT_PG_IT=true}</b>, against the throwaway
 * local DB {@code sitprep_alerts_it} built by replaying every
 * {@code db/migration/V*.sql} in numeric order through psql. Never prod.
 * Skipped on CI/Heroku. Each test rolls back.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it-pg")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/sitprep_alerts_it",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@EnabledIfEnvironmentVariable(named = "ALERT_PG_IT", matches = "true")
class AlertRecordPostgresIT {

    @Autowired private AlertSourceRevisionRepo revisions;
    @Autowired private TestEntityManager em;

    @BeforeEach
    void seed() {
        revisions.saveAll(seedRows());
        em.flush();
        em.clear();
    }

    @Test
    void wholeTokensOnly_onBothColumns() {
        assertThat(refs(revisions.findThreadCandidates(List.of("KSLC.FL.W.0012.2026"), List.of(), List.of())))
                .containsExactly("NWS:urn:oid:a.001.1@aaaaaaaaaaaa", "NWS:urn:oid:a.001.2@bbbbbbbbbbbb");
        assertThat(refs(revisions.findThreadCandidates(List.of(), List.of("urn:oid:a.001.1"), List.of())))
                .containsExactly("NWS:urn:oid:a.001.2@bbbbbbbbbbbb");
    }

    @Test
    void likeWildcardsAreLiteral() {
        assertThat(revisions.findThreadCandidates(List.of("KSLC.FL.W.____.2026"), List.of("urn:oid:a_001%"), List.of()))
                .isEmpty();
        assertThat(revisions.findThreadCandidates(List.of("%"), List.of("%"), List.of())).isEmpty();
    }

    @Test
    void disjunctsUnion_andWideInputsAreChunked() {
        assertThat(refs(revisions.findThreadCandidates(
                List.of("KSLC.FL.W.0012.2026"), List.of("urn:oid:a.001.1"), List.of("USGS:us7000q1"))))
                .containsExactly(
                        "NWS:urn:oid:a.001.1@aaaaaaaaaaaa",
                        "NWS:urn:oid:a.001.2@bbbbbbbbbbbb",
                        "USGS:us7000q1@eeeeeeeeeeee",
                        "USGS:us7000q1@ffffffffffff");

        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 450; i++) keys.add("KXXX.FL.W." + String.format("%04d", i) + ".2026");
        keys.add("KSLC.FL.A.0003.2026");
        assertThat(refs(revisions.findThreadCandidates(keys, List.of(), List.of())))
                .containsExactly("NWS:urn:oid:a.001.2@bbbbbbbbbbbb");
    }

    @Test
    void everyRevisionOfAnAlertInOneRead() {
        assertThat(refs(revisions.findByDedupeKeyIn(List.of("USGS:us7000q1"))))
                .containsExactly("USGS:us7000q1@eeeeeeeeeeee", "USGS:us7000q1@ffffffffffff");
    }
}
