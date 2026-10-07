package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.readiness.ActiveResponseResolver;
import io.sitprep.sitprepapi.repo.AlertModeStateRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PlanActivationRepo;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Single source of truth for <b>commerce suppression</b> — the monetization-
 * integrity rule (VISION_AND_SCOPE rule 6) that affiliate buy links must
 * disappear when a household is in an emergency posture. Extracted 2026-07-12
 * from {@code GoBagSupplyListService} so the Go Bag list, the 14-Day Home Kit
 * / Advanced-Readiness supply list, AND the Food Planner shopping list all
 * gate commerce through ONE decision instead of three copies.
 *
 * <p>Precedence (first match wins):</p>
 * <ol>
 *   <li>{@code household_checkin} — the household's check-in is Active;</li>
 *   <li>{@code deployed_plan} — a live plan activation for the household
 *       ({@link PlanActivationRepo#findLiveForHousehold}: the owner's, any
 *       member's, or one keyed to the household id);</li>
 *   <li>{@code area_alert} (home) — an official alert at the household's home
 *       ({@link ActiveResponseResolver#hasOfficialAlert}), the same signal
 *       Ready for More treats as an active response (release audit
 *       2026-10-07: without it, /home-kit showed buy links while the journey
 *       said "an official alert is in effect");</li>
 *   <li>{@code area_alert} (zip bucket) — the persisted {@link io.sitprep.sitprepapi.domain.AlertModeState}
 *       for the household's zip bucket is {@code alert}/{@code crisis}. Read
 *       straight from the state table by PK — deliberately NOT
 *       {@code AlertModeService.getForLatLng}, which reverse-geocodes via
 *       Nominatim on the request thread (a rate-limited network call). A
 *       missing row means calm, same semantics as lazy-create-on-read.</li>
 * </ol>
 */
@Service
public class CommerceSuppressionService {

    private final GroupRepo groupRepo;
    private final PlanActivationRepo activationRepo;
    private final AlertModeStateRepo alertModeRepo;
    private final RiskProfileService riskProfileService;

    public CommerceSuppressionService(GroupRepo groupRepo,
                                      PlanActivationRepo activationRepo,
                                      AlertModeStateRepo alertModeRepo,
                                      RiskProfileService riskProfileService) {
        this.groupRepo = groupRepo;
        this.activationRepo = activationRepo;
        this.alertModeRepo = alertModeRepo;
        this.riskProfileService = riskProfileService;
    }

    /** First matching suppression reason, or {@code null} when commerce may render. */
    public String suppressionReason(String householdId) {
        if (householdId == null) return null;
        Group household = groupRepo.findByGroupId(householdId).orElse(null);
        if (household == null) return null;

        if ("Active".equalsIgnoreCase(household.getAlert())) return "household_checkin";

        List<PlanActivation> live = activationRepo.findLiveForHousehold(household, Instant.now());
        if (live != null && !live.isEmpty()) {
            return "deployed_plan";
        }

        if (officialAlertAtHome(household)) return "area_alert";

        String zip = household.getZipCode();
        if (zip != null && zip.trim().length() >= 3) {
            String state = alertModeRepo.findById(zip.trim().substring(0, 3))
                    .map(s -> s.getState())
                    .orElse(null);
            if (AlertModeService.ALERT.equals(state) || AlertModeService.CRISIS.equals(state)) {
                return "area_alert";
            }
        }
        return null;
    }

    /** Convenience boolean for callers that don't need the reason. */
    public boolean isSuppressed(String householdId) {
        return suppressionReason(householdId) != null;
    }

    private boolean officialAlertAtHome(Group household) {
        try {
            return ActiveResponseResolver.hasOfficialAlert(riskProfileService.resolveFor(household));
        } catch (RuntimeException e) {
            // A risk-profile failure must not break the supply list; the zip
            // bucket check below still runs.
            return false;
        }
    }
}
