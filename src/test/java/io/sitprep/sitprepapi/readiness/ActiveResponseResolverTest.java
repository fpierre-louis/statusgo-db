package io.sitprep.sitprepapi.readiness;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.ActiveAlertDto;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskAdjustedRequirementDto;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskProfileDto;
import io.sitprep.sitprepapi.readiness.ActiveResponseResolver.ActiveResponse;
import io.sitprep.sitprepapi.readiness.ActiveResponseResolver.Kind;
import io.sitprep.sitprepapi.repo.PlanActivationRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActiveResponseResolverTest {

    static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    final PlanActivationRepo activations = mock(PlanActivationRepo.class);
    final ActiveResponseResolver resolver = new ActiveResponseResolver(activations);
    Group household;

    @BeforeEach
    void setUp() {
        household = new Group();
        household.setGroupId("hh-1");
        household.setGroupType("Household");
        when(activations.findLiveForHousehold(any(), any())).thenReturn(List.of());
    }

    static RiskProfileDto risk(List<ActiveAlertDto> alerts, List<RiskAdjustedRequirementDto> reqs) {
        return new RiskProfileDto("household_zip", "CA", "California", List.of(), reqs, alerts, NOW, "v");
    }

    @Test
    void calmWhenNothingIsLive() {
        assertThat(resolver.resolve(household, risk(List.of(), List.of()), NOW)).isNull();
    }

    @Test
    void liveActivationWinsAndNamesTheNewest() {
        PlanActivation newest = new PlanActivation();
        newest.setId("act-new");
        PlanActivation older = new PlanActivation();
        older.setId("act-old");
        when(activations.findLiveForHousehold(household, NOW)).thenReturn(List.of(newest, older));
        household.setAlert("Active");

        ActiveResponse r = resolver.resolve(household, null, NOW);
        assertThat(r.kind()).isEqualTo(Kind.PLAN_ACTIVATION);
        assertThat(r.title()).isEqualTo("Your plan is active");
        assertThat(r.detail()).isEqualTo("Follow your plan first. Preparedness steps can wait.");
        assertThat(r.action()).isEqualTo(ReadinessAction.OPEN_ACTIVE_SITUATION);
        assertThat(r.params()).containsEntry("activationId", "act-new");
    }

    @Test
    void openCheckInIsSecond() {
        household.setAlert("active");
        ActiveResponse r = resolver.resolve(household, risk(List.of(), List.of()), NOW);
        assertThat(r.kind()).isEqualTo(Kind.CHECK_IN);
        assertThat(r.title()).isEqualTo("Your household is checking in");
        assertThat(r.action()).isEqualTo(ReadinessAction.OPEN_CHECK_IN);
        assertThat(r.params()).containsEntry("householdId", "hh-1");
    }

    @Test
    void officialAlertUsesTheFirstHeadline() {
        var alert = new ActiveAlertDto("a1", "NWS", "Severe", "flood", "Flood Warning for Your County",
                "Area", "Move to higher ground", null, null);
        ActiveResponse r = resolver.resolve(household, risk(List.of(alert), List.of()), NOW);
        assertThat(r.kind()).isEqualTo(Kind.OFFICIAL_ALERT);
        assertThat(r.title()).isEqualTo("Flood Warning for Your County");
        assertThat(r.action()).isEqualTo(ReadinessAction.OPEN_ACTIVE_ALERTS);
    }

    @Test
    void upgradedPrecautionAloneIsAnOfficialAlert() {
        var upgraded = new RiskAdjustedRequirementDto("active_alert_flood", "flood", "l", "d", 0,
                "Safety steps", "/hazards", "active_alert_upgraded");
        ActiveResponse r = resolver.resolve(household, risk(List.of(), List.of(upgraded)), NOW);
        assertThat(r.kind()).isEqualTo(Kind.OFFICIAL_ALERT);
        assertThat(r.title()).isEqualTo("An official alert is in effect");
    }
}
