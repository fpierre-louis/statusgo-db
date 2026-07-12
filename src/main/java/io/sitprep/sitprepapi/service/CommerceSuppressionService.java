package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.repo.AlertModeStateRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PlanActivationRepo;
import org.springframework.stereotype.Service;

import java.time.Instant;

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
 *   <li>{@code deployed_plan} — the household owner has an unexpired plan
 *       activation;</li>
 *   <li>{@code area_alert} — the persisted {@link io.sitprep.sitprepapi.domain.AlertModeState}
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

    public CommerceSuppressionService(GroupRepo groupRepo,
                                      PlanActivationRepo activationRepo,
                                      AlertModeStateRepo alertModeRepo) {
        this.groupRepo = groupRepo;
        this.activationRepo = activationRepo;
        this.alertModeRepo = alertModeRepo;
    }

    /** First matching suppression reason, or {@code null} when commerce may render. */
    public String suppressionReason(String householdId) {
        if (householdId == null) return null;
        Group household = groupRepo.findByGroupId(householdId).orElse(null);
        if (household == null) return null;

        if ("Active".equalsIgnoreCase(household.getAlert())) return "household_checkin";

        String owner = household.getOwnerEmail();
        if (owner != null && activationRepo
                .findFirstActiveByOwnerEmail(owner, Instant.now()).isPresent()) {
            return "deployed_plan";
        }

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
}
