package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.EvacuationPlan;
import io.sitprep.sitprepapi.dto.RouteNotesDto;
import io.sitprep.sitprepapi.repo.EvacuationPlanRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the NON-DESTRUCTIVE route-notes update (SYSTEM_TRAPS T-17): updating
 * evacuation route notes must merge onto EXISTING plans (UPDATE by id) and
 * preserve destinations, shelters, and coordinates — it must NEVER delete-and-
 * replace (which the pre-push audit proved could wipe a user's shelter data on
 * a failed client read).
 */
@ExtendWith(MockitoExtension.class)
class EvacuationPlanServiceTest {

    private static final String OWNER = "owner@x.com";

    @Mock EvacuationPlanRepo repo;
    @Mock HouseholdResolver householdResolver;
    @Mock ActivationPlanUpdateBroadcastService broadcast;

    private EvacuationPlanService service() {
        return new EvacuationPlanService(repo, householdResolver, broadcast);
    }

    private static EvacuationPlan planWithShelter(long id) {
        EvacuationPlan p = new EvacuationPlan();
        p.setId(id);
        p.setOwnerEmail(OWNER);
        p.setName("Evacuation Plan A");
        p.setDestination("Boise Fairgrounds");
        p.setShelterName("Red Cross Shelter");
        p.setShelterAddress("12 Elm St, Boise");
        p.setShelterPhoneNumber("801-555-1212");
        p.setLat(43.6);
        p.setLng(-116.2);
        p.setTravelMode("DRIVING");
        p.setShelterInfo("Enter from the north lot");
        return p;
    }

    @Test
    void updateRouteNotes_preservesShelterAndDestination_andNeverDeletes() {
        when(householdResolver.writableTargetHousehold(OWNER)).thenReturn(null);
        EvacuationPlan existing = planWithShelter(7L);
        when(repo.findByOwnerEmail(OWNER)).thenReturn(new ArrayList<>(List.of(existing)));
        when(repo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        service().updateRouteNotes(OWNER,
                new RouteNotesDto("Main St north to I-15", "Redwood Rd south to 201", true, null));

        // Route fields updated…
        assertThat(existing.getPrimaryRouteNotes()).isEqualTo("Main St north to I-15");
        assertThat(existing.getAlternateRouteNotes()).isEqualTo("Redwood Rd south to 201");
        assertThat(existing.isOfflineMapSaved()).isTrue();
        // …every other field preserved (this is the whole point — no data loss).
        assertThat(existing.getDestination()).isEqualTo("Boise Fairgrounds");
        assertThat(existing.getShelterName()).isEqualTo("Red Cross Shelter");
        assertThat(existing.getShelterAddress()).isEqualTo("12 Elm St, Boise");
        assertThat(existing.getShelterPhoneNumber()).isEqualTo("801-555-1212");
        assertThat(existing.getLat()).isEqualTo(43.6);
        assertThat(existing.getLng()).isEqualTo(-116.2);
        assertThat(existing.getTravelMode()).isEqualTo("DRIVING");
        assertThat(existing.getShelterInfo()).isEqualTo("Enter from the north lot");
        // NEVER deletes — the delete-and-replace wipe path is not reachable here.
        verify(repo, never()).deleteByOwnerEmail(any());
        verify(repo, never()).deleteAll(any());
        verify(repo).saveAll(any());
    }

    @Test
    void updateRouteNotes_appliesToEveryPlan() {
        when(householdResolver.writableTargetHousehold(OWNER)).thenReturn(null);
        EvacuationPlan a = planWithShelter(1L);
        EvacuationPlan b = planWithShelter(2L);
        when(repo.findByOwnerEmail(OWNER)).thenReturn(new ArrayList<>(List.of(a, b)));
        when(repo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        service().updateRouteNotes(OWNER, new RouteNotesDto("P", "A", true, null));

        assertThat(a.getPrimaryRouteNotes()).isEqualTo("P");
        assertThat(b.getPrimaryRouteNotes()).isEqualTo("P");
        assertThat(b.getShelterName()).isEqualTo("Red Cross Shelter"); // still preserved
    }

    @Test
    void updateRouteNotes_noExistingPlans_createsMinimalRouteOnlyPlan_withoutDeleting() {
        when(householdResolver.writableTargetHousehold(OWNER)).thenReturn(null);
        when(repo.findByOwnerEmail(OWNER)).thenReturn(new ArrayList<>());
        when(householdResolver.baseHouseholdIdFor(OWNER)).thenReturn("hh-1");
        when(repo.save(any(EvacuationPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        List<EvacuationPlan> saved = service().updateRouteNotes(OWNER,
                new RouteNotesDto("Primary route", null, false, null));

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getPrimaryRouteNotes()).isEqualTo("Primary route");
        assertThat(saved.get(0).getHouseholdId()).isEqualTo("hh-1");
        assertThat(saved.get(0).getOwnerEmail()).isEqualTo(OWNER);
        verify(repo, never()).deleteByOwnerEmail(any());
        verify(repo).save(any(EvacuationPlan.class));
    }

    @Test
    void updateRouteNotes_lastPracticedAt_preservedWhenNotProvided() {
        when(householdResolver.writableTargetHousehold(OWNER)).thenReturn(null);
        EvacuationPlan existing = planWithShelter(7L);
        existing.setLastPracticedAt(Instant.EPOCH); // e.g. set by a future drill hook
        when(repo.findByOwnerEmail(OWNER)).thenReturn(new ArrayList<>(List.of(existing)));
        when(repo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        // The wizard sends null lastPracticedAt → the existing value must survive.
        service().updateRouteNotes(OWNER, new RouteNotesDto("P", "A", true, null));

        assertThat(existing.getLastPracticedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void updateRouteNotes_crossHousehold_targetsThatHouseholdsPlans_noDelete() {
        when(householdResolver.writableTargetHousehold(OWNER)).thenReturn("hh-target");
        EvacuationPlan existing = planWithShelter(9L);
        when(repo.findByHouseholdId("hh-target")).thenReturn(new ArrayList<>(List.of(existing)));
        when(repo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        service().updateRouteNotes(OWNER, new RouteNotesDto("P", "A", true, null));

        assertThat(existing.getPrimaryRouteNotes()).isEqualTo("P");
        verify(repo).findByHouseholdId("hh-target");
        verify(repo, never()).findByOwnerEmail(any());
        verify(repo, never()).deleteAll(any());
    }
}
