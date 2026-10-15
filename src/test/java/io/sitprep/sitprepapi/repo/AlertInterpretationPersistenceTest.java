package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.AlertInterpretationSnapshot;
import io.sitprep.sitprepapi.domain.AlertSourceRevision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S2 C3 — the V110 entities on the H2 {@code test} profile (Hibernate-built
 * schema): they persist, a revision is inserted once and never merged over,
 * {@code lastSeenAt} only moves forward, and the revision sweep never takes a
 * row a snapshot points at.
 *
 * <p>The snapshot sweep's query names {@code NotificationLog}, whose jsonb
 * column H2 cannot create; it runs on real Postgres in
 * {@code AlertInterpretationPostgresIT}.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
class AlertInterpretationPersistenceTest {

    private static final Instant T0 = Instant.parse("2026-10-14T12:00:00Z");
    private static final Instant CUTOFF = T0.minus(Duration.ofDays(30));

    @Autowired private AlertSourceRevisionRepo revisions;
    @Autowired private AlertInterpretationSnapshotRepo snapshots;
    @Autowired private TestEntityManager em;

    static AlertSourceRevision revision(String ref, Instant lastSeen) {
        AlertSourceRevision r = new AlertSourceRevision();
        r.setRevisionRef(ref);
        r.setDedupeKey(ref.substring(0, ref.indexOf('@')));
        r.setSource(ref.substring(0, ref.indexOf(':')));
        r.setUpstreamId(ref.substring(ref.indexOf(':') + 1, ref.indexOf('@')));
        r.setFingerprint("a".repeat(64));
        r.setVtecEventKeys("KSLC.FL.W.0012.2026");
        r.setReferenceIds("urn:a urn:b");
        r.setSentAt(T0);
        r.setPayload("{\"id\":\"x\"}");
        r.setFirstSeenAt(lastSeen);
        r.setLastSeenAt(lastSeen);
        return r;
    }

    static AlertInterpretationSnapshot snapshot(String ref, String fpChar, Instant createdAt) {
        AlertInterpretationSnapshot s = new AlertInterpretationSnapshot();
        s.setId(UUID.randomUUID());
        s.setFingerprint(fpChar.repeat(64));
        s.setRevisionRef(ref);
        s.setDedupeKey(ref.substring(0, ref.indexOf('@')));
        s.setPolicyVersion("policy-test");
        s.setInterpreterVersion("interp-test");
        s.setTemplateKey("NWS:Flood Warning:warning");
        s.setTemplateReviewVersion(2);
        s.setDecisionJson("{}");
        s.setContentJson("{}");
        s.setCreatedAt(createdAt);
        return s;
    }

    @Test
    void bothEntitiesRoundTrip() {
        revisions.saveAndFlush(revision("NWS:urn:1@aaaaaaaaaaaa", T0));
        AlertInterpretationSnapshot s = snapshots.saveAndFlush(snapshot("NWS:urn:1@aaaaaaaaaaaa", "b", T0));
        em.clear();

        AlertSourceRevision r = revisions.findById("NWS:urn:1@aaaaaaaaaaaa").orElseThrow();
        assertThat(r.getFingerprint()).isEqualTo("a".repeat(64));
        assertThat(r.getVtecEventKeys()).isEqualTo("KSLC.FL.W.0012.2026");
        assertThat(r.isNew()).isFalse();
        AlertInterpretationSnapshot back = snapshots.findById(s.getId()).orElseThrow();
        assertThat(back.getTemplateReviewVersion()).isEqualTo(2);
        assertThat(snapshots.findIdsByFingerprintIn(List.of("b".repeat(64), "c".repeat(64))))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getId()).isEqualTo(s.getId());
                    assertThat(row.getFingerprint()).isEqualTo("b".repeat(64));
                });
    }

    @Test
    void theCacheMissProjectionReturnsRefAndLastSeenOnly() {
        revisions.saveAndFlush(revision("NWS:urn:1@aaaaaaaaaaaa", T0));
        revisions.saveAndFlush(revision("NWS:urn:2@aaaaaaaaaaaa", T0.plusSeconds(60)));
        em.clear();

        assertThat(revisions.findLastSeenByRefIn(List.of("NWS:urn:2@aaaaaaaaaaaa", "NWS:urn:absent@aaaaaaaaaaaa")))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getRevisionRef()).isEqualTo("NWS:urn:2@aaaaaaaaaaaa");
                    assertThat(row.getLastSeenAt()).isEqualTo(T0.plusSeconds(60));
                });
    }

    @Test
    void aRevisionIsInsertedOnce_neverMergedOver() {
        revisions.saveAndFlush(revision("USGS:us1@aaaaaaaaaaaa", T0));
        em.clear();

        AlertSourceRevision again = revision("USGS:us1@aaaaaaaaaaaa", T0.plusSeconds(3600));
        assertThatThrownBy(() -> revisions.saveAndFlush(again)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void lastSeenOnlyMovesForward() {
        revisions.saveAndFlush(revision("NWS:urn:1@aaaaaaaaaaaa", T0));
        revisions.saveAndFlush(revision("NWS:urn:2@aaaaaaaaaaaa", T0.plusSeconds(7200)));

        int n = revisions.touchLastSeen(List.of("NWS:urn:1@aaaaaaaaaaaa", "NWS:urn:2@aaaaaaaaaaaa"),
                T0.plusSeconds(3600));
        em.clear();

        assertThat(n).isEqualTo(1);
        assertThat(revisions.findById("NWS:urn:1@aaaaaaaaaaaa").orElseThrow().getLastSeenAt())
                .isEqualTo(T0.plusSeconds(3600));
        assertThat(revisions.findById("NWS:urn:2@aaaaaaaaaaaa").orElseThrow().getLastSeenAt())
                .isEqualTo(T0.plusSeconds(7200));
    }

    @Test
    void theRevisionSweepTakesOnlyOldUnreferencedRows() {
        Instant old = CUTOFF.minus(Duration.ofDays(1));
        revisions.saveAndFlush(revision("NWS:urn:old@aaaaaaaaaaaa", old));
        revisions.saveAndFlush(revision("NWS:urn:held@aaaaaaaaaaaa", old));
        revisions.saveAndFlush(revision("NWS:urn:recent@aaaaaaaaaaaa", T0));
        snapshots.saveAndFlush(snapshot("NWS:urn:held@aaaaaaaaaaaa", "c", old));

        List<String> refs = revisions.findSweepableRefs(CUTOFF, PageRequest.of(0, 100));
        assertThat(refs).containsExactly("NWS:urn:old@aaaaaaaaaaaa");

        // Even handed the referenced and the recent ref, the DELETE re-checks both.
        int deleted = revisions.deleteSweepable(List.of(
                "NWS:urn:old@aaaaaaaaaaaa", "NWS:urn:held@aaaaaaaaaaaa", "NWS:urn:recent@aaaaaaaaaaaa"), CUTOFF);
        em.clear();

        assertThat(deleted).isEqualTo(1);
        assertThat(revisions.findAll()).extracting(AlertSourceRevision::getRevisionRef)
                .containsExactlyInAnyOrder("NWS:urn:held@aaaaaaaaaaaa", "NWS:urn:recent@aaaaaaaaaaaa");
    }
}
