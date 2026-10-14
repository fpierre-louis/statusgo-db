package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.AlertInterpretationSnapshot;
import io.sitprep.sitprepapi.domain.AlertSourceRevision;
import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.repo.AlertInterpretationSnapshotRepo;
import io.sitprep.sitprepapi.repo.AlertSourceRevisionRepo;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import io.sitprep.sitprepapi.util.AlertSourceFingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S2 C3 on REAL Postgres, over the replayed Flyway schema through V110: the
 * entities validate against the migration ({@code ddl-auto=validate}, every
 * entity), the recorder is idempotent against the real tables, and the
 * retention sweep never takes a row something points at.
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
class AlertInterpretationPostgresIT {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final Instant OLD = NOW.minus(Duration.ofDays(40));

    @Autowired private AlertSourceRevisionRepo revisions;
    @Autowired private AlertInterpretationSnapshotRepo snapshots;
    @Autowired private NotificationLogRepo notifications;
    @Autowired private TestEntityManager em;
    @Autowired private JdbcTemplate jdbc;

    private AlertFeedService feed;

    @BeforeEach
    void setUp() {
        NwsZoneService zones = new NwsZoneService();
        AlertIngestService ingest = new AlertIngestService(zones);
        AlertDispatchService dispatch = new AlertDispatchService(null, null, null, null, null, null, zones, null);
        dispatch.loadTemplates();
        feed = new AlertFeedService(ingest, dispatch);
    }

    private InterpretationRecorder recorder() {
        InterpretationRecorder r = new InterpretationRecorder(revisions, snapshots, () -> feed, 1024);
        r.clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return r;
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }

    private static NormalizedAlert flood() {
        return TestAlerts.nws("Flood Warning")
                .id("urn:oid:2.49.0.1.840.0.pgit.001.1")
                .sent("2026-10-14T11:55:00-07:00")
                .endsAt("2099-08-27T06:55:00Z")
                .build();
    }

    private static NormalizedAlert quake(double magnitude) {
        return TestAlerts.usgs("M " + magnitude + " - 10 km N of Somewhere, CA")
                .id("pgit7000abc")
                .magnitude(magnitude)
                .geometry(Map.of("type", "Point", "coordinates", List.of(-118.2, 34.0)))
                .build();
    }

    // ── recorder ────────────────────────────────────────────────────────

    @Test
    void twoIdenticalPollsWriteNothingTheSecondTime_andAUsgsRevisionWritesOneOfEach() {
        int revs0 = count("alert_source_revision");
        int snaps0 = count("alert_interpretation_snapshot");
        InterpretationRecorder recorder = recorder();

        recorder.record(new Snapshot(List.of(flood(), quake(5.9)), NOW, NOW));
        em.flush();
        assertThat(count("alert_source_revision")).isEqualTo(revs0 + 2);
        assertThat(count("alert_interpretation_snapshot")).isEqualTo(snaps0 + 2);

        // Same poll again — same process, then a restarted one reading the table.
        recorder.record(new Snapshot(List.of(flood(), quake(5.9)), NOW, NOW));
        recorder().record(new Snapshot(List.of(flood(), quake(5.9)), NOW, NOW));
        em.flush();
        assertThat(count("alert_source_revision")).isEqualTo(revs0 + 2);
        assertThat(count("alert_interpretation_snapshot")).isEqualTo(snaps0 + 2);

        // USGS revises the magnitude under the same id.
        InterpretationRecorder.Result r = recorder.record(new Snapshot(List.of(flood(), quake(6.2)), NOW, NOW));
        em.flush();
        assertThat(r.newRevisions()).isEqualTo(1);
        assertThat(r.newSnapshots()).isEqualTo(1);
        assertThat(count("alert_source_revision")).isEqualTo(revs0 + 3);
        assertThat(count("alert_interpretation_snapshot")).isEqualTo(snaps0 + 3);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM alert_source_revision WHERE dedupe_key = 'USGS:pgit7000abc'", Integer.class))
                .isEqualTo(2);

        em.clear();
        AlertSourceRevision stored = revisions.findById(AlertSourceFingerprint.revisionRef(flood())).orElseThrow();
        assertThat(stored.getFingerprint()).isEqualTo(AlertSourceFingerprint.fingerprint(flood()));
        assertThat(stored.getSentAt()).isEqualTo(Instant.parse("2026-10-14T18:55:00Z"));
    }

    // ── retention ───────────────────────────────────────────────────────

    private AlertSourceRevision revision(String ref, Instant lastSeen) {
        AlertSourceRevision r = new AlertSourceRevision();
        r.setRevisionRef(ref);
        r.setDedupeKey(ref.substring(0, ref.indexOf('@')));
        r.setSource("NWS");
        r.setUpstreamId(ref.substring(4, ref.indexOf('@')));
        r.setFingerprint("a".repeat(64));
        r.setPayload("{}");
        r.setFirstSeenAt(lastSeen);
        r.setLastSeenAt(lastSeen);
        return revisions.save(r);
    }

    private AlertInterpretationSnapshot snapshot(String ref, String fpChar, Instant createdAt) {
        AlertInterpretationSnapshot s = new AlertInterpretationSnapshot();
        s.setId(UUID.randomUUID());
        s.setFingerprint(fpChar.repeat(64));
        s.setRevisionRef(ref);
        s.setDedupeKey(ref.substring(0, ref.indexOf('@')));
        s.setPolicyVersion(AlertSafetyPolicy.POLICY_VERSION);
        s.setInterpreterVersion(AlertInterpreter.VERSION);
        s.setDecisionJson("{}");
        s.setContentJson("{}");
        s.setCreatedAt(createdAt);
        return snapshots.save(s);
    }

    private void notificationNaming(UUID snapshotId) {
        NotificationLog n = new NotificationLog("ana@x.com", "hazard_alert", null, "t", "b", "NWS-urn:pg",
                "/alerts", null, NOW.minus(Duration.ofDays(2)), true, null);
        n.setAlertKey("NWS:urn:pg-named");
        n.setAlertRevisionRef("NWS:urn:pg-named@aaaaaaaaaaaa");
        n.setAlertThreadKey("vtec:KSLC.FL.W.0012.2026");
        n.setInterpretationSnapshotId(snapshotId);
        notifications.save(n);
    }

    @Test
    void theSweepTakesOnlyWhatNothingPointsAt_inContractOrder() {
        revision("NWS:urn:pg-free@aaaaaaaaaaaa", OLD);
        revision("NWS:urn:pg-named@aaaaaaaaaaaa", OLD);
        revision("NWS:urn:pg-live@aaaaaaaaaaaa", NOW);       // still listed upstream
        AlertInterpretationSnapshot free = snapshot("NWS:urn:pg-free@aaaaaaaaaaaa", "1", OLD);
        AlertInterpretationSnapshot named = snapshot("NWS:urn:pg-named@aaaaaaaaaaaa", "2", OLD);
        AlertInterpretationSnapshot live = snapshot("NWS:urn:pg-live@aaaaaaaaaaaa", "3", OLD);
        notificationNaming(named.getId());
        em.flush();

        InterpretationRecorder recorder = recorder();

        // Step 2: only the snapshot no notification names and whose revision is gone upstream.
        int snapshotsDeleted = recorder.sweepSnapshotsOnce(30, 100);
        em.flush();
        em.clear();
        assertThat(snapshotsDeleted).isEqualTo(1);
        assertThat(snapshots.existsById(free.getId())).isFalse();
        assertThat(snapshots.existsById(named.getId())).as("a notification names it").isTrue();
        assertThat(snapshots.existsById(live.getId())).as("its revision is still listed").isTrue();

        // Step 3: only the revision no snapshot names any more.
        int revisionsDeleted = recorder.sweepRevisionsOnce(30, 100);
        em.flush();
        em.clear();
        assertThat(revisionsDeleted).isEqualTo(1);
        assertThat(revisions.existsById("NWS:urn:pg-free@aaaaaaaaaaaa")).isFalse();
        assertThat(revisions.existsById("NWS:urn:pg-named@aaaaaaaaaaaa")).isTrue();
        assertThat(revisions.existsById("NWS:urn:pg-live@aaaaaaaaaaaa")).isTrue();

        // Handed the named snapshot directly, the DELETE still re-checks the reference.
        assertThat(snapshots.deleteSweepable(List.of(named.getId()))).isZero();
    }

    @Test
    void theForeignKeyRefusesToDropARevisionUnderASnapshot() {
        revision("NWS:urn:pg-fk@aaaaaaaaaaaa", OLD);
        snapshot("NWS:urn:pg-fk@aaaaaaaaaaaa", "4", OLD);
        em.flush();

        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM alert_source_revision WHERE revision_ref = 'NWS:urn:pg-fk@aaaaaaaaaaaa'"))
                .hasMessageContaining("alert_interpretation_snapshot_revision_ref_fkey");
    }
}
