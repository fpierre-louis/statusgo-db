package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.PlanActivation;
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
import static org.mockito.Mockito.when;

/**
 * Commerce suppression (owner ruling 2026-10-14, applied 2026-10-15): only the
 * household's own active response — an open check-in or a live plan activation
 * — pauses buy links. An official alert at home no longer does: shopping stays
 * available, and an alert must never read as a reason to hide gear.
 */
@ExtendWith(MockitoExtension.class)
class CommerceSuppressionServiceTest {

    @Mock GroupRepo groupRepo;
    @Mock PlanActivationRepo activationRepo;

    private CommerceSuppressionService service() {
        return new CommerceSuppressionService(groupRepo, activationRepo);
    }

    @Test
    void openCheckInSuppressesFirst() {
        Group household = household();
        household.setAlert("Active");
        when(groupRepo.findByGroupId("hh-1")).thenReturn(Optional.of(household));

        assertThat(service().suppressionReason("hh-1")).isEqualTo("household_checkin");
    }

    @Test
    void liveActivationForTheHouseholdSuppresses() {
        Group household = household();
        when(groupRepo.findByGroupId("hh-1")).thenReturn(Optional.of(household));
        when(activationRepo.findLiveForHousehold(eq(household), any(Instant.class)))
                .thenReturn(List.of(new PlanActivation()));

        assertThat(service().suppressionReason("hh-1")).isEqualTo("deployed_plan");
    }

    @Test
    void anOfficialAlertAloneLeavesCommerceOn() {
        // The household is calm in its own response; whatever is in force at home
        // or in its zip bucket, the links stay (there is no alert lookup at all).
        Group household = household();
        household.setZipCode("84043");
        when(groupRepo.findByGroupId("hh-1")).thenReturn(Optional.of(household));
        when(activationRepo.findLiveForHousehold(eq(household), any(Instant.class))).thenReturn(List.of());

        assertThat(service().suppressionReason("hh-1")).isNull();
        assertThat(service().isSuppressed("hh-1")).isFalse();
    }

    @Test
    void unknownHouseholdIsNotSuppressed() {
        when(groupRepo.findByGroupId("nope")).thenReturn(Optional.empty());
        assertThat(service().suppressionReason("nope")).isNull();
        assertThat(service().suppressionReason(null)).isNull();
    }

    private static Group household() {
        Group g = new Group();
        g.setGroupId("hh-1");
        g.setGroupType("Household");
        return g;
    }
}
