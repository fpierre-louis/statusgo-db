package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.dto.AlertCardDto;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-4: {@code inAlertIds} — which ACTIVE alerts a member's fix is INSIDE, keyed
 * by the same id {@code /api/alerts/feed} ships, answered from cache only.
 */
class MemberAlertAreaServiceTest {

    /** San Carlos AZ, and the zones that cover it (same fixture point as AlertFeedServiceTest). */
    private static final double LAT = 33.3172, LNG = -110.5297;
    private static final Set<String> SAN_CARLOS = Set.of("AZZ560", "AZC007", "AZZ133");
    private static final String FUTURE = "2099-08-27T06:55:00Z";

    private static Map<String, Object> square(double lat, double lng, double half) {
        return Map.of("type", "Polygon", "coordinates", List.of(List.of(
                List.of(lng - half, lat - half), List.of(lng + half, lat - half),
                List.of(lng + half, lat + half), List.of(lng - half, lat + half),
                List.of(lng - half, lat - half))));
    }

    /** Zone-only, targets San Carlos's public zone. */
    private final NormalizedAlert heat = TestAlerts.nws("Extreme Heat Warning")
            .id("urn:oid:heat").endsAt(FUTURE).ugc(List.of("AZZ560")).build();
    /** Polygon around the point. */
    private final NormalizedAlert stormHere = TestAlerts.nws("Flood Warning")
            .id("urn:oid:flood-here").endsAt(FUTURE).geometry(square(LAT, LNG, 0.1))
            .ugc(List.of("AZC007")).build();
    /** Polygon ~25 km away whose UGC still lists the member's county. */
    private final NormalizedAlert stormNearby = TestAlerts.nws("Flood Warning")
            .id("urn:oid:flood-near").endsAt(FUTURE).geometry(square(LAT + 0.22, LNG, 0.05))
            .ugc(List.of("AZC007")).build();
    /** Zone-only, somewhere else entirely. */
    private final NormalizedAlert elsewhere = TestAlerts.nws("Extreme Heat Warning")
            .id("urn:oid:maine").endsAt(FUTURE).ugc(List.of("MEZ024")).build();

    private NwsZoneService zones;
    private AlertIngestService ingest;
    private AlertDispatchService dispatch;
    private MemberAlertAreaService service;

    @BeforeEach
    void setUp() throws Exception {
        zones = new NwsZoneService();
        zones.setEnabled(true);
        zones.seedPointZones(LAT, LNG, SAN_CARLOS);
        ingest = new AlertIngestService(zones);
        dispatch = new AlertDispatchService(null, null, null, null, null, null, zones, null);
        dispatch.loadTemplates();
        ingest.setSnapshotForTest(List.of(heat, stormHere, stormNearby, elsewhere));
        service = new MemberAlertAreaService(ingest, dispatch, zones);
    }

    @Test
    void idsAreTheFeedCardIdsForTheSamePoint() {
        List<String> inside = service.idsFor(service.activeAreas(), LAT, LNG);
        List<String> feedIds = new AlertFeedService(ingest, dispatch).feedFor(LAT, LNG)
                .alerts().stream().map(AlertCardDto::id).toList();

        assertThat(inside).containsExactlyInAnyOrder("urn:oid:heat", "urn:oid:flood-here");
        assertThat(feedIds).as("every inAlertIds entry joins to a feed card").containsAll(inside);
    }

    @Test
    void geometryBeatsZones() {
        // flood-near's UGC names the member's county, and the feed may well show
        // it as nearby — but the member is not inside its polygon.
        assertThat(service.idsFor(service.activeAreas(), LAT, LNG)).doesNotContain("urn:oid:flood-near");
    }

    @Test
    void emptyListWhenLocatedAndInsideNothing() {
        double lat = 33.9, lng = -110.9;
        zones.seedPointZones(lat, lng, Set.of("AZZ999"));
        assertThat(service.idsFor(service.activeAreas(), lat, lng)).isEmpty();
    }

    @Test
    void unknownZonesAreNullNotEmptyAndNeverANetworkCall() {
        NwsZoneService cold = mock(NwsZoneService.class);
        when(cold.cachedZoneCodesForPoint(anyDouble(), anyDouble())).thenReturn(Optional.empty());
        MemberAlertAreaService svc = new MemberAlertAreaService(ingest, dispatch, cold);

        assertThat(svc.idsFor(svc.activeAreas(), LAT, LNG)).isNull();
        verify(cold).warmPoint(LAT, LNG);
        verify(cold, never()).zoneCodesForPoint(anyDouble(), anyDouble());
    }

    @Test
    void expiredCancelledAndPointAlertsHaveNoInside() {
        NormalizedAlert expired = TestAlerts.nws("Extreme Heat Warning")
                .id("urn:oid:old").endsAt("2026-09-01T00:00:00Z").ugc(List.of("AZZ560")).build();
        NormalizedAlert cancelled = TestAlerts.nws("Extreme Heat Warning")
                .id("urn:oid:cancel").endsAt(FUTURE).messageType("Cancel").ugc(List.of("AZZ560")).build();
        NormalizedAlert quake = TestAlerts.usgs("M5.1 near San Carlos")
                .geometry(Map.of("type", "Point", "coordinates", List.of(LNG, LAT, 10.0))).build();

        List<MemberAlertAreaService.AreaAlert> areas = List.of(
                new MemberAlertAreaService.AreaAlert(expired, null, false, Set.of("AZZ560")),
                new MemberAlertAreaService.AreaAlert(cancelled, null, false, Set.of("AZZ560")),
                new MemberAlertAreaService.AreaAlert(quake, null, true, Set.of()));
        assertThat(MemberAlertAreaService.match(areas, LAT, LNG, SAN_CARLOS,
                Instant.parse("2026-09-27T12:00:00Z"))).isEmpty();
    }

    @Test
    void thePreparedListIsReusedUntilTheSnapshotChanges() {
        List<MemberAlertAreaService.AreaAlert> first = service.activeAreas();
        assertThat(service.activeAreas()).isSameAs(first);
        ingest.setSnapshotForTest(List.of(heat));
        assertThat(service.activeAreas()).isNotSameAs(first).hasSize(1);
    }
}
