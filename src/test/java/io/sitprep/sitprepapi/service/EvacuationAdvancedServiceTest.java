package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.EvacuationPlan;
import io.sitprep.sitprepapi.dto.EvacuationAdvancedDto;
import io.sitprep.sitprepapi.dto.EvacuationAdvancedDto.EvacMetricDto;
import io.sitprep.sitprepapi.repo.EvacuationPlanRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * ADVANCED evacuation readiness guards: the ADVANCED framing, the two
 * route metrics derived from evacuation-plan fields (alternate route + offline
 * maps), blank-note handling, and the CTA routing. Baseline decoupling is
 * structural (this service is never injected into the readiness engine).
 */
@ExtendWith(MockitoExtension.class)
class EvacuationAdvancedServiceTest {

    private static final String HH = "hh-1";

    @Mock EvacuationPlanRepo evacuationPlanRepo;

    private EvacuationAdvancedService service() {
        return new EvacuationAdvancedService(evacuationPlanRepo);
    }

    private static EvacuationPlan plan(String primary, String alternate, boolean offline) {
        EvacuationPlan p = new EvacuationPlan();
        p.setHouseholdId(HH);
        p.setPrimaryRouteNotes(primary);
        p.setAlternateRouteNotes(alternate);
        p.setOfflineMapSaved(offline);
        return p;
    }

    private static EvacMetricDto metric(EvacuationAdvancedDto dto, String key) {
        return dto.metrics().stream().filter(m -> m.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void tierIsAdvanced_andMetricsAreTheTwoRouteChecks() {
        when(evacuationPlanRepo.findByHouseholdId(HH)).thenReturn(List.of());

        EvacuationAdvancedDto dto = service().getForHousehold(HH);

        assertThat(dto.tier()).isEqualTo("ADVANCED");
        assertThat(dto.context()).isEqualTo("evacuation_advanced");
        assertThat(dto.metrics()).extracting(EvacMetricDto::key)
                .containsExactly("alternate_route", "offline_maps");
    }

    @Test
    void noPlans_bothUnmet_zeroPercent_withCtasToWizard() {
        when(evacuationPlanRepo.findByHouseholdId(HH)).thenReturn(List.of());

        EvacuationAdvancedDto dto = service().getForHousehold(HH);

        assertThat(dto.percentComplete()).isZero();
        assertThat(dto.hasPrimaryRoute()).isFalse();
        EvacMetricDto alt = metric(dto, "alternate_route");
        assertThat(alt.satisfied()).isFalse();
        assertThat(alt.cta()).isNotNull();
        assertThat(alt.route()).isEqualTo("/evacuation-wizard");
        assertThat(metric(dto, "offline_maps").satisfied()).isFalse();
    }

    @Test
    void alternateRoutePresent_marksItSatisfied_hidesCta_andRaisesPercent() {
        when(evacuationPlanRepo.findByHouseholdId(HH))
                .thenReturn(List.of(plan("Head north on Main", "Back roads via Elm St", false)));

        EvacuationAdvancedDto dto = service().getForHousehold(HH);

        assertThat(metric(dto, "alternate_route").satisfied()).isTrue();
        assertThat(metric(dto, "alternate_route").cta()).isNull();
        assertThat(metric(dto, "offline_maps").satisfied()).isFalse();
        assertThat(dto.percentComplete()).isEqualTo(50);
        assertThat(dto.hasPrimaryRoute()).isTrue();
    }

    @Test
    void offlineMapSavedOnAnyPlan_marksItSatisfied() {
        when(evacuationPlanRepo.findByHouseholdId(HH))
                .thenReturn(List.of(plan("Primary", null, false), plan(null, null, true)));

        EvacuationAdvancedDto dto = service().getForHousehold(HH);

        assertThat(metric(dto, "offline_maps").satisfied()).isTrue();
    }

    @Test
    void bothPresent_hundredPercent() {
        when(evacuationPlanRepo.findByHouseholdId(HH))
                .thenReturn(List.of(plan("Primary", "Alternate", true)));

        EvacuationAdvancedDto dto = service().getForHousehold(HH);

        assertThat(dto.percentComplete()).isEqualTo(100);
    }

    @Test
    void blankNotesDoNotCount() {
        when(evacuationPlanRepo.findByHouseholdId(HH))
                .thenReturn(List.of(plan("  ", "   ", false)));

        EvacuationAdvancedDto dto = service().getForHousehold(HH);

        assertThat(metric(dto, "alternate_route").satisfied()).isFalse();
        assertThat(dto.hasPrimaryRoute()).isFalse();
    }
}
