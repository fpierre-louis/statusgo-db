package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.AlertModeState;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.ActiveAlertDto;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskAdjustedRequirementDto;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskProfileDto;
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

/**
 * Commerce suppression must agree with Ready for More's active response
 * (release audit 2026-10-07): a live activation (owner, member, or keyed to the
 * household), an open check-in, or an official alert at home all pause buy links.
 */
@ExtendWith(MockitoExtension.class)
class CommerceSuppressionServiceTest {

    @Mock GroupRepo groupRepo;
    @Mock PlanActivationRepo activationRepo;
    @Mock AlertModeStateRepo alertModeRepo;
    @Mock RiskProfileService riskProfileService;

    private CommerceSuppressionService service() {
        return new CommerceSuppressionService(groupRepo, activationRepo, alertModeRepo, riskProfileService);
    }

    @Test
    void openCheckInSuppressesFirst() {
        Group household = household();
        household.setAlert("Active");
        when(groupRepo.findByGroupId("hh-1")).thenReturn(Optional.of(household));

        assertThat(service().suppressionReason("hh-1")).isEqualTo("household_checkin");
        verifyNoInteractions(riskProfileService, alertModeRepo);
    }

    @Test
    void liveActivationForTheHouseholdSuppresses() {
        Group household = household();
        when(groupRepo.findByGroupId("hh-1")).thenReturn(Optional.of(household));
        when(activationRepo.findLiveForHousehold(eq(household), any(Instant.class)))
                .thenReturn(List.of(new PlanActivation()));

        assertThat(service().suppressionReason("hh-1")).isEqualTo("deployed_plan");
        verifyNoInteractions(riskProfileService, alertModeRepo);
    }

    @Test
    void officialAlertAtHomeSuppressesEvenWhenTheZipBucketIsCalm() {
        Group household = household();
        when(groupRepo.findByGroupId("hh-1")).thenReturn(Optional.of(household));
        when(activationRepo.findLiveForHousehold(eq(household), any(Instant.class))).thenReturn(List.of());
        when(riskProfileService.resolveFor(household)).thenReturn(risk(List.of(new ActiveAlertDto("a1", "NWS", "Severe", "flood", "Flash Flood Warning", "Home", "Move to higher ground", null, null, "Flash Flood Warning")), List.of()));

        assertThat(service().suppressionReason("hh-1")).isEqualTo("area_alert");
        verifyNoInteractions(alertModeRepo);
    }

    @Test
    void urgentPrecautionAtHomeSuppresses() {
        Group household = household();
        when(groupRepo.findByGroupId("hh-1")).thenReturn(Optional.of(household));
        when(activationRepo.findLiveForHousehold(eq(household), any(Instant.class))).thenReturn(List.of());
        RiskAdjustedRequirementDto upgraded = new RiskAdjustedRequirementDto(
                "active_alert_flood", "flood", "Flood alert in effect — act now", "Move to higher ground",
                0, "Safety steps", "/hazards?scope=home", "active_alert_upgraded");
        when(riskProfileService.resolveFor(household)).thenReturn(risk(List.of(), List.of(upgraded)));

        assertThat(service().suppressionReason("hh-1")).isEqualTo("area_alert");
    }

    @Test
    void zipBucketAlertStillSuppresses() {
        Group household = household();
        household.setZipCode("84043");
        when(groupRepo.findByGroupId("hh-1")).thenReturn(Optional.of(household));
        when(activationRepo.findLiveForHousehold(eq(household), any(Instant.class))).thenReturn(List.of());
        when(riskProfileService.resolveFor(household)).thenReturn(risk(List.of(), List.of()));
        AlertModeState state = new AlertModeState();
        state.setState(AlertModeService.ALERT);
        when(alertModeRepo.findById("840")).thenReturn(Optional.of(state));

        assertThat(service().suppressionReason("hh-1")).isEqualTo("area_alert");
    }

    @Test
    void calmEverywhereLeavesCommerceOn() {
        Group household = household();
        household.setZipCode("84043");
        when(groupRepo.findByGroupId("hh-1")).thenReturn(Optional.of(household));
        when(activationRepo.findLiveForHousehold(eq(household), any(Instant.class))).thenReturn(List.of());
        when(riskProfileService.resolveFor(household)).thenReturn(risk(List.of(), List.of()));
        when(alertModeRepo.findById("840")).thenReturn(Optional.empty());

        assertThat(service().suppressionReason("hh-1")).isNull();
    }

    @Test
    void riskProfileFailureFallsThroughToTheZipCheck() {
        Group household = household();
        household.setZipCode("84043");
        when(groupRepo.findByGroupId("hh-1")).thenReturn(Optional.of(household));
        when(activationRepo.findLiveForHousehold(eq(household), any(Instant.class))).thenReturn(List.of());
        when(riskProfileService.resolveFor(household)).thenThrow(new IllegalStateException("snapshot down"));
        when(alertModeRepo.findById("840")).thenReturn(Optional.empty());

        assertThat(service().suppressionReason("hh-1")).isNull();
    }

    private static RiskProfileDto risk(List<ActiveAlertDto> alerts, List<RiskAdjustedRequirementDto> reqs) {
        return new RiskProfileDto("saved_home", "840", "Utah", List.of(), reqs, alerts, Instant.now(), "test");
    }

    private static Group household() {
        Group group = new Group();
        group.setGroupId("hh-1");
        group.setGroupType("Household");
        group.setOwnerEmail("owner@example.com");
        group.setMemberEmails(List.of("owner@example.com", "member@example.com"));
        return group;
    }
}
