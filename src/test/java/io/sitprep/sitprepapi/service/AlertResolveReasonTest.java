package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.AlertPost;
import io.sitprep.sitprepapi.repo.AlertPostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import io.sitprep.sitprepapi.util.Vtec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S2 C6 (contract §6/§7): {@code resolveOnce} records WHY it cleared a
 * dispatched post — {@code issuer_cancelled}, {@code replaced}, {@code expired}
 * or {@code not_listed} — from the issuer's own messages in the live snapshot,
 * so the post can still tell a cancel from a replacement after the cancelling
 * message has left the feed (S1 F3 follow-up).
 *
 * <p>Absence is never read as a cancel: an alert gone from a fresh poll with no
 * message naming it is {@code not_listed}.</p>
 */
class AlertResolveReasonTest {

    private AlertIngestService ingest;
    private AlertDispatchService dispatcher;
    private final Map<String, List<AlertPost>> tracked = new HashMap<>();

    @BeforeEach
    void setUp() {
        ingest = mock(AlertIngestService.class);
        AlertPostRepo alertPostRepo = mock(AlertPostRepo.class);
        dispatcher = new AlertDispatchService(ingest, alertPostRepo, mock(PostService.class),
                mock(UserInfoRepo.class), mock(NominatimGeocodeService.class),
                mock(NotificationService.class), null, null);
        when(alertPostRepo.findActiveAlertIds()).thenAnswer(inv -> new ArrayList<>(tracked.keySet()));
        when(alertPostRepo.findActiveByAlertId(anyString()))
                .thenAnswer(inv -> tracked.getOrDefault((String) inv.getArgument(0), List.of()));
        when(alertPostRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private AlertPost track(String alertId, String geocell, Instant expiresAt) {
        AlertPost ap = new AlertPost();
        ap.setAlertId(alertId);
        ap.setGeocellId(geocell);
        ap.setPostId((long) (tracked.size() + 100));
        ap.setExpiresAt(expiresAt);
        tracked.computeIfAbsent(alertId, k -> new ArrayList<>()).add(ap);
        return ap;
    }

    private void snapshot(boolean nwsFresh, NormalizedAlert... alerts) {
        Instant now = Instant.now();
        when(ingest.getSnapshot()).thenReturn(new Snapshot(List.of(alerts), now, now,
                Map.of("NWS", now, "USGS", now, "FEMA", now),
                nwsFresh ? Set.of("NWS", "USGS", "FEMA") : Set.of("USGS", "FEMA")));
    }

    private static Instant later() { return Instant.now().plus(Duration.ofHours(2)); }

    private static Instant earlier() { return Instant.now().minus(Duration.ofMinutes(5)); }

    private static Vtec vtec(String action) {
        return Vtec.parse("/O." + action + ".KSLC.FF.W.0042.261013T1800Z-261014T0300Z/").orElseThrow();
    }

    @Test
    void anUpdateWhoseVtecCancelsTheEventIsAnIssuerCancel() {
        AlertPost ap = track("NWS-urn:oid:a", "84117", later());
        snapshot(true, TestAlerts.nws("Flash Flood Warning").id("urn:oid:a-can").messageType("Update")
                .references(List.of("urn:oid:a")).vtec(List.of(vtec("CAN"))).build());

        dispatcher.resolveOnce();

        assertThat(ap.getResolvedAt()).isNotNull();
        assertThat(ap.getResolvedReason()).isEqualTo("issuer_cancelled");
    }

    @Test
    void aCapCancelOrAllClearIsAnIssuerCancel() {
        AlertPost cancelled = track("NWS-urn:oid:b", "84117", later());
        AlertPost allClear = track("NWS-urn:oid:c", "84117", later());
        snapshot(true,
                TestAlerts.nws("Tornado Warning").id("urn:oid:b-x").messageType("Cancel")
                        .references(List.of("urn:oid:b")).build(),
                TestAlerts.nws("Tornado Warning").id("urn:oid:c-x").messageType("Update").response("AllClear")
                        .references(List.of("urn:oid:c")).build());

        dispatcher.resolveOnce();

        assertThat(cancelled.getResolvedReason()).isEqualTo("issuer_cancelled");
        assertThat(allClear.getResolvedReason()).isEqualTo("issuer_cancelled");
    }

    @Test
    void anUpdateThatCarriesTheEventOnIsAReplacement() {
        AlertPost con = track("NWS-urn:oid:d", "84117", later());
        AlertPost plain = track("NWS-urn:oid:e", "84117", later());
        AlertPost upgraded = track("NWS-urn:oid:f", "84117", later());
        snapshot(true,
                TestAlerts.nws("Flash Flood Warning").id("urn:oid:d-2").messageType("Update")
                        .references(List.of("urn:oid:d")).vtec(List.of(vtec("CON"))).build(),
                TestAlerts.nws("Flood Advisory").id("urn:oid:e-2").messageType("Update")
                        .references(List.of("urn:oid:e")).build(),
                // A watch upgraded to a warning: UPG on the watch, NEW on the warning.
                TestAlerts.nws("Flash Flood Warning").id("urn:oid:f-2").messageType("Update")
                        .references(List.of("urn:oid:f"))
                        .vtec(List.of(Vtec.parse("/O.UPG.KSLC.FF.A.0009.261013T1800Z-261014T0300Z/").orElseThrow(),
                                vtec("NEW"))).build());

        dispatcher.resolveOnce();

        assertThat(con.getResolvedReason()).isEqualTo("replaced");
        assertThat(plain.getResolvedReason()).isEqualTo("replaced");
        assertThat(upgraded.getResolvedReason()).isEqualTo("replaced");
    }

    @Test
    void goneFromAFreshPollWithNothingNamingItIsNotListed() {
        AlertPost ap = track("NWS-urn:oid:g", "84117", later());
        snapshot(true);

        dispatcher.resolveOnce();

        assertThat(ap.getResolvedReason()).isEqualTo("not_listed");
    }

    @Test
    void pastItsEndTimeIsExpired_listedOrNot_freshOrNot() {
        AlertPost stillListed = track("NWS-urn:oid:h", "84117", earlier());
        AlertPost sourceDown = track("USGS-us7000h", "84117", earlier());
        AlertPost goneFresh = track("NWS-urn:oid:i", "84117", earlier());
        Instant now = Instant.now();
        when(ingest.getSnapshot()).thenReturn(new Snapshot(
                List.of(TestAlerts.nws("Heat Advisory").id("urn:oid:h").build()), now, now,
                Map.of("NWS", now, "USGS", now, "FEMA", now), Set.of("NWS", "FEMA")));

        dispatcher.resolveOnce();

        assertThat(stillListed.getResolvedReason()).isEqualTo("expired");
        assertThat(sourceDown.getResolvedReason()).isEqualTo("expired");
        assertThat(goneFresh.getResolvedReason()).isEqualTo("expired");
    }

    @Test
    void theIssuersEdgeOutranksTheEndTime() {
        // Cancelled, and its declared end has since passed too: still a cancel.
        AlertPost ap = track("NWS-urn:oid:j", "84117", earlier());
        snapshot(true, TestAlerts.nws("Flash Flood Warning").id("urn:oid:j-can").messageType("Cancel")
                .references(List.of("urn:oid:j")).build());

        dispatcher.resolveOnce();

        assertThat(ap.getResolvedReason()).isEqualTo("issuer_cancelled");
    }

    @Test
    void everyGeocellRowOfTheAlertGetsTheSameReason() {
        AlertPost a = track("NWS-urn:oid:k", "84117", later());
        AlertPost b = track("NWS-urn:oid:k", "84121", later());
        snapshot(true, TestAlerts.nws("Flash Flood Warning").id("urn:oid:k-can").messageType("Cancel")
                .references(List.of("urn:oid:k")).build());

        dispatcher.resolveOnce();

        assertThat(a.getResolvedReason()).isEqualTo("issuer_cancelled");
        assertThat(b.getResolvedReason()).isEqualTo("issuer_cancelled");
    }

    @Test
    void aDrillOrAnAckNamingALiveWarningCancelsNothing() {
        // Only a real, public issuer statement may end another alert
        // (AlertDerivations.reverseEdgeIndex's rule).
        AlertPost drill = track("NWS-urn:oid:l", "84117", later());
        AlertPost acked = track("NWS-urn:oid:m", "84117", later());
        snapshot(true,
                TestAlerts.nws("Tornado Warning").id("urn:oid:l-x").status("Exercise").messageType("Cancel")
                        .references(List.of("urn:oid:l")).build(),
                TestAlerts.nws("Tornado Warning").id("urn:oid:m-x").messageType("Ack")
                        .references(List.of("urn:oid:m")).build());

        dispatcher.resolveOnce();

        // Both left a fresh poll, so they resolve — but as not_listed.
        assertThat(drill.getResolvedReason()).isEqualTo("not_listed");
        assertThat(acked.getResolvedReason()).isEqualTo("not_listed");
    }

    @Test
    void anAlertStillListedAndInDateStaysUnresolvedWithNoReason() {
        AlertPost ap = track("NWS-urn:oid:n", "84117", later());
        snapshot(true, TestAlerts.nws("Flash Flood Warning").id("urn:oid:n").build());

        dispatcher.resolveOnce();

        assertThat(ap.getResolvedAt()).isNull();
        assertThat(ap.getResolvedReason()).isNull();
    }
}
