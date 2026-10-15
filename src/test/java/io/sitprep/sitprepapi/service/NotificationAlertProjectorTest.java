package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.AlertInterpretationSnapshot;
import io.sitprep.sitprepapi.domain.AlertSourceRevision;
import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.dto.AlertCardDto;
import io.sitprep.sitprepapi.dto.NotificationInboxRowDto;
import io.sitprep.sitprepapi.repo.AlertInterpretationSnapshotRepo;
import io.sitprep.sitprepapi.repo.AlertSourceRevisionRepo;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code row.alert} (S2 contract §5): lifecycle re-evaluated now, primary
 * action only while listed + current + on the delivered revision, legacy rows
 * by referenceId, agency/check-in rows none — and a page costs one lifecycle
 * call and one read each of revisions and snapshots, however many rows.
 */
class NotificationAlertProjectorTest {

    private static final String THREAD = "VTEC:KSLC.FA.W.0007.2026";
    private static final String W1 = "NWS:urn:oid:w1";
    private static final String W1_REF = W1 + "@aaaaaaaaaaaa";
    private static final String W1_OLD_REF = W1 + "@bbbbbbbbbbbb";
    private static final String ENDED = "NWS:urn:oid:ended";
    private static final String ENDED_REF = ENDED + "@cccccccccccc";
    private static final String LEGACY = "NWS:urn:oid:legacy";
    private static final String LEGACY_REF = LEGACY + "@dddddddddddd";

    private final AtomicLong ids = new AtomicLong();
    private final ObjectMapper json = new ObjectMapper();
    private AlertRecordService records;
    private AlertSourceRevisionRepo revisions;
    private AlertInterpretationSnapshotRepo snapshots;
    private NotificationAlertProjector projector;
    private final UUID snapshotId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        records = mock(AlertRecordService.class);
        when(records.lifecycleFor(any())).thenAnswer(inv -> {
            Collection<String> refs = inv.getArgument(0);
            Map<String, AlertRecordService.Lifecycle> out = new HashMap<>();
            for (String ref : refs) {
                if (ref.startsWith(W1)) out.put(ref, lifecycle(W1, W1_REF, true, "active", true, null));
                if (ref.startsWith(ENDED)) out.put(ref, lifecycle(ENDED, ENDED_REF, false, "cancelled", false, "vtec_can"));
                if (ref.startsWith(LEGACY)) out.put(ref, lifecycle(LEGACY, LEGACY_REF, true, "active", true, null));
            }
            return out;
        });

        revisions = mock(AlertSourceRevisionRepo.class);
        Map<String, AlertSourceRevision> revs = new HashMap<>();
        for (String ref : List.of(W1_REF, W1_OLD_REF, ENDED_REF, LEGACY_REF)) {
            AlertSourceRevision r = new AlertSourceRevision();
            r.setRevisionRef(ref);
            r.setDedupeKey(ref.substring(0, ref.lastIndexOf('@')));
            r.setPayload(json.writeValueAsString(TestAlerts.nws("Flood Warning")
                    .id(ref.substring(4, ref.lastIndexOf('@')))
                    .area("Salt Lake; Davis; Weber")
                    .ends("2099-10-14T19:00:00Z")
                    .build()));
            revs.put(ref, r);
        }
        when(revisions.findAllById(any())).thenAnswer(inv -> {
            Iterable<String> want = inv.getArgument(0);
            List<AlertSourceRevision> out = new java.util.ArrayList<>();
            for (String k : want) if (revs.containsKey(k)) out.add(revs.get(k));
            return out;
        });

        snapshots = mock(AlertInterpretationSnapshotRepo.class);
        AlertInterpretationSnapshot snap = new AlertInterpretationSnapshot();
        snap.setId(snapshotId);
        snap.setContentJson("{\"interpreter\":{\"primaryAction\":{\"text\":\"Move to higher ground now.\","
                + "\"kind\":\"official\",\"attribution\":\"National Weather Service\"}},"
                + "\"sourceIdentity\":{\"name\":\"National Weather Service\"}}");
        when(snapshots.findAllById(any())).thenReturn(List.of(snap));

        AlertDispatchService dispatch = new AlertDispatchService(null, null, null, null, null, null,
                new NwsZoneService(), null);
        dispatch.loadTemplates();
        projector = new NotificationAlertProjector(records, revisions, snapshots, dispatch);
    }

    @Test
    void aPageResolvesEveryRowInFixedStatements() {
        NotificationLog current = alertRow(W1, W1_REF, snapshotId);
        NotificationLog olderRevision = alertRow(W1, W1_OLD_REF, snapshotId);
        NotificationLog ended = alertRow(ENDED, ENDED_REF, snapshotId);
        NotificationLog legacy = row("hazard_alert", "NWS-urn:oid:legacy");
        NotificationLog agency = row("hazard_alert", "agency-alert:9");
        agency.setAlertKey("AGENCY:9");
        NotificationLog checkIn = row("alert", "group-1");

        Map<Long, NotificationInboxRowDto.AlertRow> out =
                projector.forRows(List.of(current, olderRevision, ended, legacy, agency, checkIn));

        verify(records, times(1)).lifecycleFor(any());
        verify(revisions, times(1)).findAllById(any());
        verify(snapshots, times(1)).findAllById(any());

        // Current, listed, on the revision it delivered: the snapshot's action, verbatim.
        NotificationInboxRowDto.AlertRow c = out.get(current.getId());
        assertThat(c.dedupeKey()).isEqualTo(W1);
        assertThat(c.threadKey()).isEqualTo(THREAD);
        assertThat(c.lifecycle()).isEqualTo(new NotificationInboxRowDto.Lifecycle("active", true, null));
        assertThat(c.primaryAction()).isEqualTo(new AlertCardDto.PrimaryAction(
                "Move to higher ground now.", "official", "National Weather Service"));
        assertThat(c.issuer()).isEqualTo("National Weather Service");
        assertThat(c.eventLabel()).isEqualTo("Flood Warning");
        assertThat(c.tier()).isEqualTo("warning");
        assertThat(c.areaLabel()).isEqualTo("Salt Lake + 2 more");
        assertThat(c.actions()).extracting(AlertCardDto.Action::type).containsExactly("OPEN_ALERT_DETAIL");

        // An older revision of the same live alert is a record: no action.
        assertThat(out.get(olderRevision.getId()).primaryAction()).isNull();
        assertThat(out.get(olderRevision.getId()).lifecycle().current()).isTrue();

        // Ended now, whatever it said when delivered: no action, state says so.
        NotificationInboxRowDto.AlertRow e = out.get(ended.getId());
        assertThat(e.primaryAction()).isNull();
        assertThat(e.lifecycle()).isEqualTo(new NotificationInboxRowDto.Lifecycle("cancelled", false, "vtec_can"));

        // Legacy: resolved from referenceId; no delivered revision or snapshot, so no action.
        NotificationInboxRowDto.AlertRow l = out.get(legacy.getId());
        assertThat(l.dedupeKey()).isEqualTo(LEGACY);
        assertThat(l.revisionRef()).isNull();
        assertThat(l.primaryAction()).isNull();
        assertThat(l.eventLabel()).isEqualTo("Flood Warning");

        assertThat(out).doesNotContainKeys(agency.getId(), checkIn.getId());
    }

    @Test
    void aReferenceNamingNoAlertGivesNoAlert() {
        NotificationLog odd = row("hazard_alert", "something-else");
        assertThat(projector.forRows(List.of(odd))).isEmpty();
    }

    // ------------------------------------------------------------------

    private static AlertRecordService.Lifecycle lifecycle(String key, String ref, boolean listed,
                                                          String state, boolean current, String reason) {
        return new AlertRecordService.Lifecycle(key, ref, listed, new AlertCardDto.LifecycleInfo(
                state, current, true, reason, THREAD, List.of(), null, null, null));
    }

    private NotificationLog alertRow(String key, String ref, UUID snapshot) {
        NotificationLog n = row("hazard_alert", key.replace("NWS:", "NWS-"));
        n.setAlertKey(key);
        n.setAlertRevisionRef(ref);
        n.setAlertThreadKey(THREAD);
        n.setInterpretationSnapshotId(snapshot);
        return n;
    }

    private NotificationLog row(String type, String referenceId) {
        NotificationLog n = new NotificationLog("me@x.com", type, null, "t", "b", referenceId, "/hazards", null,
                Instant.parse("2026-10-14T12:00:00Z"), true, null);
        n.setId(ids.incrementAndGet());
        return n;
    }

    @SuppressWarnings("unused")
    private static NormalizedAlert none() {
        return null;
    }
}
