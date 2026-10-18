package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.domain.GeoPoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** R19 (owner, 2026-10-15): the containment rule for severe NWS pushes. */
class PushTargetingTest {

    // ~11 km square around (40.40, -111.85); GeoJSON is [lng, lat].
    static final Map<String, Object> SQUARE = Map.of("type", "Polygon", "coordinates", List.of(List.of(
            List.of(-111.90, 40.35), List.of(-111.80, 40.35), List.of(-111.80, 40.45),
            List.of(-111.90, 40.45), List.of(-111.90, 40.35))));
    static final double[] COORD = { -111.85, 40.40 };

    static UserInfo at(String email, double lat, double lng) {
        UserInfo u = new UserInfo();
        u.setUserEmail(email);
        u.setFcmtoken("tok-" + email);
        u.setLastKnownLat(lat);
        u.setLastKnownLng(lng);
        return u;
    }

    static List<String> emails(List<UserInfo> us) {
        return us.stream().map(UserInfo::getUserEmail).toList();
    }

    private static PushTargeting.Plan plan(NormalizedAlert a, List<UserInfo> people, List<Group> homes,
                                           List<UserInfo> homeUsers, java.util.function.BiFunction<Double, Double, Optional<Set<String>>> zones) {
        return PushTargeting.plan(a, COORD, people, homes,
                wanted -> homeUsers.stream().filter(u -> wanted.contains(u.getUserEmail().toLowerCase())).toList(), zones);
    }

    @Test
    void anOutlineReachesInsideAndTheBuffer_notTheOldRadius() {
        NormalizedAlert a = TestAlerts.nws("Tornado Warning").geometry(SQUARE).build();
        var p = plan(a, List.of(
                at("inside@x", 40.40, -111.85),
                at("edge@x", 40.40, -111.79),     // ~0.85 km outside the edge → buffer
                at("near@x", 40.40, -111.60),     // ~17 km: the old 80 km rule pushed this one
                at("far@x", 41.50, -111.85)),
                List.of(), List.of(), (la, ln) -> Optional.empty());
        assertThat(emails(p.here())).containsExactlyInAnyOrder("inside@x", "edge@x");
        assertThat(p.audit().basis()).isEqualTo("outline+5km");
        assertThat(p.audit().proximityWould()).isEqualTo(3);
        assertThat(p.audit().droppedVsProximity()).isEqualTo(1);
        assertThat(p.audit().fallbackProximity()).isZero();
    }

    @Test
    void aZoneOnlyAlertMatchesZones_andAnUnknownPointFailsOpenToTheOldRadius() {
        NormalizedAlert a = TestAlerts.nws("Flood Warning").geometry(null).ugc(List.of("UTZ106")).build();
        var p = plan(a, List.of(
                at("lehi@x", 40.39, -111.85),         // zones known: UTZ106 → yes
                at("provo@x", 40.23, -111.66),        // zones known: elsewhere → no (even though within 80 km)
                at("newfix@x", 40.50, -111.90),       // zones unknown, within 80 km → fallback yes
                at("unknownfar@x", 42.0, -111.0)),    // zones unknown, beyond 80 km → no
                List.of(), List.of(), (lat, lng) -> lat == 40.39 ? Optional.of(Set.of("UTZ106", "UTC049"))
                        : lat == 40.23 ? Optional.of(Set.of("UTZ108", "UTC049")) : Optional.empty());
        assertThat(emails(p.here())).containsExactlyInAnyOrder("lehi@x", "newfix@x");
        assertThat(p.audit().basis()).isEqualTo("zones");
        assertThat(p.audit().fallbackProximity()).isEqualTo(1);
    }

    @Test
    void noAreaAtAllIsTheOldRuleForEveryone() {
        NormalizedAlert a = TestAlerts.nws("Tornado Warning").geometry(null).ugc(List.of()).same(List.of()).build();
        var p = plan(a, List.of(at("near@x", 40.40, -111.60), at("far@x", 41.50, -111.85)),
                List.of(), List.of(), (la, ln) -> Optional.empty());
        assertThat(emails(p.here())).containsExactly("near@x");
        assertThat(p.audit().basis()).isEqualTo("proximity");
    }

    @Test
    void aHouseholdWhoseHomeIsInsideIsReached_labelledAtHome_neverTwice_neverWithoutAToken() {
        NormalizedAlert a = TestAlerts.nws("Tornado Warning").geometry(SQUARE).build();
        Group home = new Group();
        home.setGroupType("Household");
        home.setHomeLocation(new GeoPoint(40.41, -111.86));       // inside
        home.setOwnerEmail("Mom@x");
        home.setMemberEmails(new ArrayList<>(List.of("kid@x", "inside@x", "notoken@x")));
        Group away = new Group();
        away.setGroupType("Household");
        away.setHomeLocation(new GeoPoint(41.50, -111.85));      // far outside
        away.setOwnerEmail("other@x");

        UserInfo mom = at("mom@x", 45.0, -100.0);                 // travelling, far away
        UserInfo kid = at("kid@x", 45.0, -100.0);
        UserInfo noToken = at("notoken@x", 40.41, -111.86);
        noToken.setFcmtoken(null);
        var p = plan(a, List.of(at("inside@x", 40.40, -111.85)), List.of(home, away),
                List.of(mom, kid, noToken, at("other@x", 41.5, -111.85)), (la, ln) -> Optional.empty());

        assertThat(emails(p.here())).containsExactly("inside@x");
        assertThat(emails(p.atHome())).containsExactlyInAnyOrder("mom@x", "kid@x");
        assertThat(p.audit().reachedHome()).isEqualTo(2);
        assertThat(p.audit().addedVsProximity()).isEqualTo(2);
    }

    @Test
    void anUnreadableOutlineFailsOpenToTheOldRadius() {
        NormalizedAlert a = TestAlerts.nws("Tornado Warning")
                .geometry(Map.of("type", "Polygon", "coordinates", List.of())).build();
        var p = plan(a, List.of(at("near@x", 40.40, -111.60)), List.of(), List.of(), (la, ln) -> Optional.empty());
        assertThat(emails(p.here())).containsExactly("near@x");
        assertThat(p.audit().fallbackProximity()).isEqualTo(1);
    }
}
