package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.EvacuationPlan;
import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.domain.MeetingPlaceTier;
import io.sitprep.sitprepapi.gamification.TokenEventPublisher;
import io.sitprep.sitprepapi.repo.EvacuationPlanRepo;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import io.sitprep.sitprepapi.util.MainChoice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** "Main" is one choice per household (evacuation plan clarity, 2026-10-20). */
class MainChoiceTest {

    private static final String HH = "hh-1";

    private static MeetingPlace spot(long id, MeetingPlaceTier tier, boolean main, String owner) {
        MeetingPlace p = new MeetingPlace();
        p.setId(id);
        p.setMeetingTier(tier);
        p.setDeploy(main);
        p.setOwnerEmail(owner);
        p.setHouseholdId(HH);
        return p;
    }

    @Test
    @DisplayName("keepOne leaves only the kept row flagged and never the ineligible one")
    void keepOneLeavesOne() {
        MeetingPlace a = spot(1, MeetingPlaceTier.OUTSIDE_HOME, true, "a@x.com");
        MeetingPlace b = spot(2, MeetingPlaceTier.OUT_OF_TOWN, true, "a@x.com");
        MeetingPlace safe = spot(3, MeetingPlaceTier.INDOOR_SAFE_ROOM, true, "a@x.com");

        MainChoice.keepOne(List.of(a, b, safe), MeetingPlace::getId, MeetingPlace::isDeploy,
                MeetingPlace::setDeploy, MeetingPlaceService::canBeMain, 2L);

        assertThat(a.isDeploy()).isFalse();
        assertThat(b.isDeploy()).isTrue();
        assertThat(safe.isDeploy()).isFalse();
    }

    @Test
    @DisplayName("setMain on a meeting spot clears the household's other Main")
    void setMainClearsSiblings() {
        MeetingPlaceRepo repo = mock(MeetingPlaceRepo.class);
        MeetingPlaceService service = new MeetingPlaceService(repo, mock(HouseholdResolver.class),
                mock(ActivationPlanUpdateBroadcastService.class), mock(TokenEventPublisher.class));
        MeetingPlace outside = spot(1, MeetingPlaceTier.OUTSIDE_HOME, true, "a@x.com");
        MeetingPlace town = spot(2, MeetingPlaceTier.OUT_OF_TOWN, false, "a@x.com");
        when(repo.findById(2L)).thenReturn(Optional.of(town));
        when(repo.save(any(MeetingPlace.class))).thenAnswer(i -> i.getArgument(0));
        when(repo.findByHouseholdId(HH)).thenReturn(List.of(outside, town));

        service.setMain(2L, "a@x.com");

        assertThat(town.isDeploy()).isTrue();
        assertThat(outside.isDeploy()).isFalse();
    }

    @Test
    @DisplayName("a member who neither wrote the spot nor admins the household gets 403")
    void setMainRequiresAuthorOrAdmin() {
        MeetingPlaceRepo repo = mock(MeetingPlaceRepo.class);
        MeetingPlaceService service = new MeetingPlaceService(repo, mock(HouseholdResolver.class),
                mock(ActivationPlanUpdateBroadcastService.class), mock(TokenEventPublisher.class));
        HouseholdAccessService access = mock(HouseholdAccessService.class);
        when(access.canWriteHousehold("member@x.com", HH)).thenReturn(false);
        service.setHouseholdAccess(access);
        when(repo.findById(2L)).thenReturn(Optional.of(spot(2, MeetingPlaceTier.OUT_OF_TOWN, false, "a@x.com")));

        assertThatThrownBy(() -> service.setMain(2L, "member@x.com"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    @DisplayName("an admin may set Main on a spot someone else wrote; the safe room never")
    void adminMaySetMainButNotOnSafeRoom() {
        MeetingPlaceRepo repo = mock(MeetingPlaceRepo.class);
        MeetingPlaceService service = new MeetingPlaceService(repo, mock(HouseholdResolver.class),
                mock(ActivationPlanUpdateBroadcastService.class), mock(TokenEventPublisher.class));
        HouseholdAccessService access = mock(HouseholdAccessService.class);
        when(access.canWriteHousehold("admin@x.com", HH)).thenReturn(true);
        service.setHouseholdAccess(access);
        when(repo.findById(3L)).thenReturn(Optional.of(spot(3, MeetingPlaceTier.INDOOR_SAFE_ROOM, false, "a@x.com")));

        assertThatThrownBy(() -> service.setMain(3L, "admin@x.com"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    @DisplayName("a bulk destination save keeps only the first Main")
    void bulkDestinationsKeepOneMain() {
        EvacuationPlanRepo repo = mock(EvacuationPlanRepo.class);
        HouseholdResolver resolver = mock(HouseholdResolver.class);
        EvacuationPlanService service = new EvacuationPlanService(repo, resolver,
                mock(ActivationPlanUpdateBroadcastService.class), mock(TokenEventPublisher.class));
        EvacuationPlan a = new EvacuationPlan();
        a.setDeploy(true);
        EvacuationPlan b = new EvacuationPlan();
        b.setDeploy(true);
        when(repo.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        service.saveAllEvacuationPlans("a@x.com", new java.util.ArrayList<>(List.of(a, b)));

        assertThat(a.isDeploy()).isTrue();
        assertThat(b.isDeploy()).isFalse();
    }
}
