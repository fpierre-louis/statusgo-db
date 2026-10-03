package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.EvacuationPlan;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.domain.MeetingPlaceTier;
import io.sitprep.sitprepapi.domain.OriginLocation;
import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.dto.MapPlaceDto;
import io.sitprep.sitprepapi.repo.EvacuationPlanRepo;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import io.sitprep.sitprepapi.repo.OriginLocationRepo;
import io.sitprep.sitprepapi.repo.PlanActivationRepo;
import io.sitprep.sitprepapi.util.PlanRowReconciler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The map shows the PLAN, not everything saved (owner rulings Q-1..Q-4,
 * docs/epics/map_and_slate/PLAN-LOCATIONS-AUDIT-2026-09-29.md).
 */
class MapPlaceServicePlanTest {

    private MeetingPlaceRepo meets;
    private EvacuationPlanRepo evacs;
    private OriginLocationRepo origins;
    private PlanActivationRepo activations;
    private MapPlaceService service;
    private Group hh;

    @BeforeEach
    void setUp() {
        meets = mock(MeetingPlaceRepo.class);
        evacs = mock(EvacuationPlanRepo.class);
        origins = mock(OriginLocationRepo.class);
        activations = mock(PlanActivationRepo.class);
        service = new MapPlaceService(meets, evacs, origins, activations);

        hh = new Group();
        hh.setGroupId("hh");
        hh.setGroupName("The Lees");
        hh.setGroupType("Household");
        hh.setOwnerEmail("owner@x.com");
        hh.setLatitude(40.30);
        hh.setLongitude(-111.70);

        when(meets.findByHouseholdId("hh")).thenReturn(List.of(
                meeting(1L, "Park", MeetingPlaceTier.OUTSIDE_HOME, false),
                meeting(2L, "UVU", MeetingPlaceTier.OUT_OF_TOWN, true),
                meeting(3L, "byu", MeetingPlaceTier.OTHER, false)));
        when(evacs.findByHouseholdId("hh")).thenReturn(List.of(
                shelter(10L, "Rec Center", false), shelter(11L, "High school", false)));
        when(origins.findByHouseholdId("hh")).thenReturn(List.of(
                origin(20L, "Home", 40.3001, -111.7001),   // the home itself
                origin(21L, "Work", 40.40, -111.80),
                origin(22L, "Kids' school", 40.35, -111.75)));
    }

    @Test
    void withNoDeploymentTheMapShowsThePlansPrimaryPicksAndEveryStartingPoint() {
        List<MapPlaceDto> places = service.forHousehold(hh, "owner@x.com");

        assertThat(places).extracting(MapPlaceDto::id)
                .containsExactly("group:hh", "meetup:2", "shelter:10", "start:21", "start:22");
        assertThat(places).extracting(MapPlaceDto::role)
                .containsExactly("home", "meeting", "shelter", "start", "start");
    }

    @Test
    void aLiveDeploymentShowsOnlyWhatItSelected() {
        PlanActivation live = new PlanActivation();
        live.setId("act-1");
        live.setMeetingPlaceId(3L);
        live.setEvacPlanId(null); // stay put: no shelter selected
        live.setActivatedAt(Instant.now());
        when(activations.findLiveForHousehold(any(), any())).thenReturn(List.of(live));
        when(meets.findById(3L)).thenReturn(Optional.of(meeting(3L, "byu", MeetingPlaceTier.OTHER, false)));

        List<MapPlaceDto> places = service.forHousehold(hh, "owner@x.com");

        assertThat(places).extracting(MapPlaceDto::id)
                .containsExactly("group:hh", "meetup:3", "start:21", "start:22");
        assertThat(places.get(1).role()).isEqualTo("selected-meeting");
        assertThat(places).extracting(MapPlaceDto::role)
                .containsExactly("home", "selected-meeting", "start", "start");
    }

    @Test
    void theStartingPointADeploymentChoseIsMarked() {
        PlanActivation live = new PlanActivation();
        live.setId("act-2");
        live.setActivatedAt(Instant.now());
        live.getOriginLocationIds().add(22L); // Kids' school
        when(activations.findLiveForHousehold(any(), any())).thenReturn(List.of(live));

        List<MapPlaceDto> places = service.forHousehold(hh, "owner@x.com");

        assertThat(places).filteredOn(p -> p.id().startsWith("start:"))
                .extracting(MapPlaceDto::id, MapPlaceDto::role)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("start:21", "start"),
                        org.assertj.core.groups.Tuple.tuple("start:22", "selected-start"));
    }

    @Test
    void aGroupThatIsNotAHouseholdGetsNoPlanPlaces() {
        Group hoa = new Group();
        hoa.setGroupId("hoa");
        hoa.setGroupType("HOA/Neighborhood");
        hoa.setOwnerEmail("owner@x.com");
        assertThat(service.forHousehold(hoa, "owner@x.com")).isEmpty();
    }

    @Test
    void savingThePlanKeepsTheIdsOfRowsItStillContains() {
        List<MeetingPlace> existing = List.of(
                meeting(1L, "Park", null, false), meeting(2L, "UVU", null, true));
        MeetingPlace edited = meeting(2L, "UVU (north lot)", null, true);
        MeetingPlace added = meeting(999_999L, "New spot", null, false); // a client temp id
        List<MeetingPlace> incoming = new ArrayList<>(List.of(edited, added));

        List<MeetingPlace> toDelete = PlanRowReconciler.reconcile(existing, incoming,
                MeetingPlace::getId, MeetingPlace::setId);

        assertThat(toDelete).extracting(MeetingPlace::getId).containsExactly(1L);
        assertThat(edited.getId()).isEqualTo(2L);     // updated in place — a live deployment keeps it
        assertThat(added.getId()).isNull();           // a foreign id never reaches another row
    }

    private static MeetingPlace meeting(Long id, String name, MeetingPlaceTier tier, boolean deploy) {
        MeetingPlace m = new MeetingPlace();
        m.setId(id);
        m.setName(name);
        m.setMeetingTier(tier);
        m.setDeploy(deploy);
        m.setLat(40.31);
        m.setLng(-111.71);
        return m;
    }

    private static EvacuationPlan shelter(Long id, String name, boolean deploy) {
        EvacuationPlan e = new EvacuationPlan();
        e.setId(id);
        e.setShelterName(name);
        e.setDeploy(deploy);
        e.setLat(40.32);
        e.setLng(-111.72);
        return e;
    }

    private static OriginLocation origin(Long id, String name, double lat, double lng) {
        OriginLocation o = new OriginLocation();
        o.setId(id);
        o.setName(name);
        o.setLat(lat);
        o.setLng(lng);
        return o;
    }
}
