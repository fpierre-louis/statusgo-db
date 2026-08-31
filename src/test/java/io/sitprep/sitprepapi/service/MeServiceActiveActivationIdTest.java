package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.repo.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MeServiceActiveActivationIdTest {

    private final PlanActivationRepo planActivationRepo = mock(PlanActivationRepo.class);
    private final MeService service = new MeService(
            mock(UserInfoRepo.class),
            mock(GroupRepo.class),
            mock(DemographicRepo.class),
            mock(MealPlanDataRepo.class),
            mock(EvacuationPlanRepo.class),
            mock(MeetingPlaceRepo.class),
            mock(OriginLocationRepo.class),
            mock(EmergencyContactGroupRepo.class),
            planActivationRepo,
            mock(PostRepo.class),
            mock(GroupReadStateRepo.class),
            mock(GroupPostRepo.class),
            mock(GroupMutePrefRepo.class),
            mock(HouseholdRitualRepo.class),
            mock(MeSubfetchService.class),
            mock(UserInfoService.class),
            mock(PlatformAccessService.class),
            mock(GoBagService.class),
            mock(HouseholdReadinessService.class),
            new ObjectMapper(),
            mock(AgencyStaffService.class)
    );

    @Test
    void householdMemberSeesNewestActivationFromAnyBaseHouseholdMember() {
        Instant now = Instant.parse("2026-08-31T18:00:00Z");
        Group household = household("owner@example.com", "owner@example.com", "spouse@example.com", "teen@example.com");
        PlanActivation ownerActivation = activation("act-owner", "owner@example.com", now.minus(8, ChronoUnit.MINUTES));
        PlanActivation teenActivation = activation("act-teen", "teen@example.com", now.minus(2, ChronoUnit.MINUTES));

        when(planActivationRepo.findActiveByOwnerEmail("spouse@example.com", now)).thenReturn(List.of());
        when(planActivationRepo.findActiveByOwnerEmail("owner@example.com", now)).thenReturn(List.of(ownerActivation));
        when(planActivationRepo.findActiveByOwnerEmail("teen@example.com", now)).thenReturn(List.of(teenActivation));

        String activationId = service.resolveActiveActivationIdForHome("spouse@example.com", household, now);

        assertThat(activationId).isEqualTo("act-teen");
    }

    @Test
    void userWithoutBaseHouseholdFallsBackToOwnActiveActivation() {
        Instant now = Instant.parse("2026-08-31T18:00:00Z");
        PlanActivation selfActivation = activation("act-self", "solo@example.com", now.minus(1, ChronoUnit.MINUTES));
        when(planActivationRepo.findActiveByOwnerEmail("solo@example.com", now)).thenReturn(List.of(selfActivation));

        String activationId = service.resolveActiveActivationIdForHome("solo@example.com", null, now);

        assertThat(activationId).isEqualTo("act-self");
    }

    private static Group household(String ownerEmail, String... memberEmails) {
        Group group = new Group();
        group.setGroupId("hh-1");
        group.setGroupType("Household");
        group.setOwnerEmail(ownerEmail);
        group.setMemberEmails(List.of(memberEmails));
        return group;
    }

    private static PlanActivation activation(String id, String ownerEmail, Instant activatedAt) {
        PlanActivation activation = new PlanActivation();
        activation.setId(id);
        activation.setOwnerEmail(ownerEmail);
        activation.setActivatedAt(activatedAt);
        activation.setExpiresAt(activatedAt.plus(1, ChronoUnit.HOURS));
        return activation;
    }
}
