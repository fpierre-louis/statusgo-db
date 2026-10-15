package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.AlertSourceRevision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2 C4 — the batched thread lookup over {@code alert_source_revision}, on the
 * H2 {@code test} profile. The same assertions run on real Postgres in
 * {@code AlertRecordPostgresIT}.
 *
 * <p>The columns are space-separated token lists, so the one property that
 * matters is WHOLE-TOKEN matching: an id that is a prefix of another id, or a
 * key that contains LIKE wildcards, must never pull in a stranger's message.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
class AlertSourceRevisionThreadQueryTest {

    private static final Instant T0 = Instant.parse("2026-10-14T12:00:00Z");

    @Autowired private AlertSourceRevisionRepo revisions;
    @Autowired private TestEntityManager em;

    static AlertSourceRevision row(String dedupeKey, String fp, String vtecKeys, String referenceIds) {
        AlertSourceRevision r = new AlertSourceRevision();
        r.setRevisionRef(dedupeKey + "@" + fp);
        r.setDedupeKey(dedupeKey);
        r.setSource(dedupeKey.substring(0, dedupeKey.indexOf(':')));
        r.setUpstreamId(dedupeKey.substring(dedupeKey.indexOf(':') + 1));
        r.setFingerprint(fp.repeat(64 / fp.length() + 1).substring(0, 64));
        r.setVtecEventKeys(vtecKeys);
        r.setReferenceIds(referenceIds);
        r.setSentAt(T0);
        r.setPayload("{}");
        r.setFirstSeenAt(T0);
        r.setLastSeenAt(T0);
        return r;
    }

    @BeforeEach
    void seed() {
        revisions.saveAll(seedRows());
        em.flush();
        em.clear();
    }

    /** Shared with the Postgres IT so both databases are asked the same thing. */
    static List<AlertSourceRevision> seedRows() {
        List<AlertSourceRevision> rows = new ArrayList<>();
        rows.add(row("NWS:urn:oid:a.001.1", "aaaaaaaaaaaa", "KSLC.FL.W.0012.2026", null));
        rows.add(row("NWS:urn:oid:a.001.2", "bbbbbbbbbbbb", "KSLC.FL.W.0012.2026 KSLC.FL.A.0003.2026",
                "urn:oid:a.001.1"));
        // A prefix trap on both columns.
        rows.add(row("NWS:urn:oid:a.001.12", "cccccccccccc", "KSLC.FL.W.0012.20261", "urn:oid:a.001.12x"));
        // A wildcard trap: '_' and '%' are literal in an id.
        rows.add(row("NWS:urn:oid:aX001Y", "dddddddddddd", "KSLC.FL.W.9999.2026", "urn:oid:aZ001Q"));
        rows.add(row("USGS:us7000q1", "eeeeeeeeeeee", null, null));
        rows.add(row("USGS:us7000q1", "ffffffffffff", null, null));
        return rows;
    }

    static List<String> refs(List<AlertSourceRevision> rows) {
        return rows.stream().map(AlertSourceRevision::getRevisionRef).sorted().toList();
    }

    @Test
    void aVtecKeyMatchesWholeTokensOnly() {
        assertThat(refs(revisions.findThreadCandidates(List.of("KSLC.FL.W.0012.2026"), List.of(), List.of())))
                .containsExactly("NWS:urn:oid:a.001.1@aaaaaaaaaaaa", "NWS:urn:oid:a.001.2@bbbbbbbbbbbb");
        assertThat(refs(revisions.findThreadCandidates(List.of("KSLC.FL.A.0003.2026"), List.of(), List.of())))
                .containsExactly("NWS:urn:oid:a.001.2@bbbbbbbbbbbb");
    }

    @Test
    void aReferencedIdMatchesWholeTokensOnly() {
        assertThat(refs(revisions.findThreadCandidates(List.of(), List.of("urn:oid:a.001.1"), List.of())))
                .containsExactly("NWS:urn:oid:a.001.2@bbbbbbbbbbbb");
    }

    @Test
    void likeWildcardsInAKeyAreLiteral() {
        assertThat(revisions.findThreadCandidates(List.of("KSLC.FL.W.____.2026"), List.of("urn:oid:a_001%"), List.of()))
                .isEmpty();
        assertThat(revisions.findThreadCandidates(List.of("%"), List.of("%"), List.of())).isEmpty();
    }

    @Test
    void dedupeKeysMatchExactly_andTheDisjunctsUnionWithoutDuplicates() {
        assertThat(refs(revisions.findThreadCandidates(
                List.of("KSLC.FL.W.0012.2026"), List.of("urn:oid:a.001.1"), List.of("USGS:us7000q1", "NWS:urn:oid:a.001.1"))))
                .containsExactly(
                        "NWS:urn:oid:a.001.1@aaaaaaaaaaaa",
                        "NWS:urn:oid:a.001.2@bbbbbbbbbbbb",
                        "USGS:us7000q1@eeeeeeeeeeee",
                        "USGS:us7000q1@ffffffffffff");
    }

    @Test
    void nothingAskedIsNothingFound() {
        assertThat(revisions.findThreadCandidates(List.of(), List.of(), List.of())).isEmpty();
        assertThat(revisions.findThreadCandidates(List.of(" ", ""), List.of(""), List.of())).isEmpty();
    }

    @Test
    void wideInputsAreChunked_notDropped() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 450; i++) keys.add("KXXX.FL.W." + String.format("%04d", i) + ".2026");
        keys.add("KSLC.FL.A.0003.2026");
        assertThat(refs(revisions.findThreadCandidates(keys, List.of(), List.of())))
                .containsExactly("NWS:urn:oid:a.001.2@bbbbbbbbbbbb");
    }

    @Test
    void everyRevisionOfAnAlertComesBackInOneRead() {
        assertThat(refs(revisions.findByDedupeKeyIn(List.of("USGS:us7000q1", "NWS:urn:oid:a.001.2"))))
                .containsExactly(
                        "NWS:urn:oid:a.001.2@bbbbbbbbbbbb",
                        "USGS:us7000q1@eeeeeeeeeeee",
                        "USGS:us7000q1@ffffffffffff");
    }
}
