package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.EvacuationPlan;
import io.sitprep.sitprepapi.dto.EvacuationAdvancedDto;
import io.sitprep.sitprepapi.dto.EvacuationAdvancedDto.EvacMetricDto;
import io.sitprep.sitprepapi.repo.EvacuationPlanRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * ADVANCED evacuation readiness (mirrors {@link HomeStockpileService}). Reads the
 * household's evacuation plans and reports the two ADVANCED route metrics — an
 * ALTERNATE route and OFFLINE MAPS.
 *
 * <p>Standalone advisory surface: it is exposed on its own endpoint and is NOT
 * injected into {@code HouseholdReadinessService}, so these metrics can never
 * lower baseline {@code readinessPercent}. Only the baseline primary-route signal
 * lives in the readiness pillar.</p>
 */
@Service
public class EvacuationAdvancedService {

    static final String CONTEXT = "evacuation_advanced";
    static final String TIER = "ADVANCED";
    static final String ROUTE = "/evacuation-wizard";

    private final EvacuationPlanRepo evacuationPlanRepo;

    public EvacuationAdvancedService(EvacuationPlanRepo evacuationPlanRepo) {
        this.evacuationPlanRepo = evacuationPlanRepo;
    }

    @Transactional(readOnly = true)
    public EvacuationAdvancedDto getForHousehold(String householdId) {
        List<EvacuationPlan> plans = householdId == null ? List.of()
                : evacuationPlanRepo.findByHouseholdId(householdId);

        boolean hasPrimary   = plans.stream().anyMatch(p -> notBlank(p.getPrimaryRouteNotes()));
        boolean hasAlternate = plans.stream().anyMatch(p -> notBlank(p.getAlternateRouteNotes()));
        boolean offlineMaps  = plans.stream().anyMatch(EvacuationPlan::isOfflineMapSaved);

        List<EvacMetricDto> metrics = List.of(
                new EvacMetricDto("alternate_route", "Alternate route",
                        "A second way out if your primary road is blocked.",
                        hasAlternate, hasAlternate ? null : "Add an alternate route", ROUTE),
                new EvacMetricDto("offline_maps", "Offline maps saved",
                        "Download your route in Google or Apple Maps so it works with no cell signal.",
                        offlineMaps, offlineMaps ? null : "Save offline maps", ROUTE)
        );
        long done = metrics.stream().filter(EvacMetricDto::satisfied).count();
        int percentComplete = metrics.isEmpty() ? 0
                : (int) Math.round(100.0 * done / metrics.size());

        return new EvacuationAdvancedDto(
                CONTEXT, TIER, "Advanced evacuation",
                "Go beyond a single route — a backup way out and offline maps for when the "
                        + "network drops. Advanced prep that never lowers your baseline readiness.",
                percentComplete, hasPrimary, metrics, Instant.now());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
