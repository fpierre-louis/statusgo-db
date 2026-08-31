package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.repo.AlertModeStateRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PlanActivationRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommerceSuppressionServiceTest {

    @Mock GroupRepo groupRepo;
    @Mock PlanActivationRepo activationRepo;
    @Mock AlertModeStateRepo alertModeRepo;

    private CommerceSuppressionService service() {
        return new CommerceSuppressionService(groupRepo, activationRepo, alertModeRepo);
    }

    @Test
    void deployedPlanSuppressesCommerceWhenActivationWasLaunchedByHouseholdMember() {
        Group household = household("Owner@Example.com", "owner@example.com", "member@example.com");
        when(groupRepo.findByGroupId("hh-1")).thenReturn(Optional.of(household));
        when(activationRepo.findActiveByOwnerEmail(eq("owner@example.com"), any(Instant.class)))
                .thenReturn(List.of());
        when(activationRepo.findActiveByOwnerEmail(eq("member@example.com"), any(Instant.class)))
                .thenReturn(List.of(activation("act-member")));

        String reason = service().suppressionReason("hh-1");

        assertThat(reason).isEqualTo("deployed_plan");
        verifyNoInteractions(alertModeRepo);
    }

    @Test
    void noActivationForOwnerOrMembersLeavesCommerceUnsuppressedWhenAreaIsCalm() {
        Group household = household("owner@example.com", "owner@example.com", "member@example.com");
        household.setZipCode("84043");
        when(groupRepo.findByGroupId("hh-1")).thenReturn(Optional.of(household));
        when(activationRepo.findActiveByOwnerEmail(eq("owner@example.com"), any(Instant.class)))
                .thenReturn(List.of());
        when(activationRepo.findActiveByOwnerEmail(eq("member@example.com"), any(Instant.class)))
                .thenReturn(List.of());
        when(alertModeRepo.findById("840")).thenReturn(Optional.empty());

        String reason = service().suppressionReason("hh-1");

        assertThat(reason).isNull();
    }

    private static Group household(String ownerEmail, String... memberEmails) {
        Group group = new Group();
        group.setGroupId("hh-1");
        group.setOwnerEmail(ownerEmail);
        group.setMemberEmails(List.of(memberEmails));
        return group;
    }

    private static PlanActivation activation(String id) {
        PlanActivation activation = new PlanActivation();
        activation.setId(id);
        return activation;
    }
}
