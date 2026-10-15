package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** R19 dry run: the containment rule it measures, counted against proximity. */
class PushTargetingDryRunTest {

    // A ~11 km square around (40.40, -111.85) — GeoJSON is [lng, lat].
    private static final Map<String, Object> SQUARE = Map.of("type", "Polygon", "coordinates", List.of(List.of(
            List.of(-111.90, 40.35), List.of(-111.80, 40.35), List.of(-111.80, 40.45),
            List.of(-111.90, 40.45), List.of(-111.90, 40.35))));

    private static UserInfo at(String email, double lat, double lng) {
        UserInfo u = new UserInfo();
        u.setUserEmail(email);
        u.setLastKnownLat(lat);
        u.setLastKnownLng(lng);
        return u;
    }

    @Test
    void anOutlineCountsInsideAndTheBufferButNotFarAway() {
        NormalizedAlert a = TestAlerts.nws("Tornado Warning").geometry(SQUARE).build();
        List<UserInfo> people = List.of(
                at("inside@x", 40.40, -111.85),          // inside
                at("edge@x", 40.40, -111.79),             // ~0.85 km east of the edge: buffer
                at("near@x", 40.40, -111.60),             // ~17 km east: outside the buffer, inside 80 km
                at("far@x", 41.50, -111.85));             // ~120 km north
        // Proximity (80 km of the centre) reached inside, edge, near.
        var c = PushTargetingDryRun.compare(a, people, Set.of("inside@x", "edge@x", "near@x"), (lat, lng) -> Optional.empty());
        assertThat(c.containment()).isEqualTo(2);
        assertThat(c.both()).isEqualTo(2);
        assertThat(c.proximityOnly()).isEqualTo(1);     // near@x: today's over-push
        assertThat(c.containmentOnly()).isZero();
        assertThat(c.basis()).isEqualTo("outline+5km");
    }

    @Test
    void aZoneOnlyAlertUsesCachedZones_andAnUncachedPointIsUnknownNotOutside() {
        NormalizedAlert a = TestAlerts.nws("Flood Warning").geometry(null).ugc(List.of("UTZ106")).build();
        List<UserInfo> people = List.of(
                at("lehi@x", 40.39, -111.85),
                at("elsewhere@x", 39.0, -110.0),
                at("uncached@x", 40.0, -111.0));
        var c = PushTargetingDryRun.compare(a, people, Set.of(), (lat, lng) ->
                lat == 40.39 ? Optional.of(Set.of("UTZ106", "UTC049"))
                        : lat == 39.0 ? Optional.of(Set.of("UTZ999")) : Optional.empty());
        assertThat(c.containment()).isEqualTo(1);
        assertThat(c.containmentOnly()).isEqualTo(1);   // proximity missed lehi@x
        assertThat(c.unknown()).isEqualTo(1);
        assertThat(c.basis()).isEqualTo("zones");
    }
}
