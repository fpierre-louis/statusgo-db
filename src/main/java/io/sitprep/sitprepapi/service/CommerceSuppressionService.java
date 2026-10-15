package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PlanActivationRepo;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Single source of truth for <b>commerce suppression</b>: when the affiliate
 * buy links on the Go Bag list, the 14-Day Home Kit / readiness supply list and
 * the Food Planner shopping list are paused. One decision, three surfaces.
 *
 * <p><b>Only the household's OWN active response pauses them</b> (owner ruling
 * 2026-10-14, "allow shopping under an active alert … if anything give higher
 * recommendations", applied 2026-10-15). First match wins:</p>
 * <ol>
 *   <li>{@code household_checkin} — the household's check-in is Active;</li>
 *   <li>{@code deployed_plan} — a live plan activation for the household
 *       ({@link PlanActivationRepo#findLiveForHousehold}: the owner's, any
 *       member's, or one keyed to the household id).</li>
 * </ol>
 * <p>Then the family is responding: an order would not arrive in time, and the
 * screen gets simpler, not richer. An official alert at home, or the area's
 * alert mode, NO LONGER pauses them. People reasonably buy hazard gear when a
 * watch or warning is issued, and hiding it helped no one (the old
 * {@code area_alert} reason is gone).</p>
 *
 * <p>Not affected by this rule, and still in force: sponsored content in the
 * feed is suppressed during alert/crisis modes (VISION_AND_SCOPE monetization
 * integrity rule 1), and every affiliate link carries its "Affiliated" pill
 * (rule 6).</p>
 */
@Service
public class CommerceSuppressionService {

    private final GroupRepo groupRepo;
    private final PlanActivationRepo activationRepo;

    public CommerceSuppressionService(GroupRepo groupRepo, PlanActivationRepo activationRepo) {
        this.groupRepo = groupRepo;
        this.activationRepo = activationRepo;
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
        return null;
    }

    /** Convenience boolean for callers that don't need the reason. */
    public boolean isSuppressed(String householdId) {
        return suppressionReason(householdId) != null;
    }
}
