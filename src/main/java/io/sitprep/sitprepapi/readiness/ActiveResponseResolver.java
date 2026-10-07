package io.sitprep.sitprepapi.readiness;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.ActiveAlertDto;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskProfileDto;
import io.sitprep.sitprepapi.repo.PlanActivationRepo;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Gate 1 of the readiness journey (CONTRACT.md §3b): is something live for
 * this household right now? When it is, preparedness steps wait — no next
 * step, no reminders, no commerce tools.
 *
 * <p>First match wins: a live plan activation, then an open household
 * check-in, then an official alert at home (the risk profile's active alerts
 * or any {@code active_alert_upgraded} precaution).</p>
 */
@Component
public class ActiveResponseResolver {

    public enum Kind { PLAN_ACTIVATION, CHECK_IN, OFFICIAL_ALERT }

    public record ActiveResponse(Kind kind, String title, String detail,
                                 ReadinessAction action, Map<String, String> params) {}

    static final String WAIT_DETAIL = "Follow your plan first. Preparedness steps can wait.";

    private final PlanActivationRepo planActivationRepo;

    public ActiveResponseResolver(PlanActivationRepo planActivationRepo) {
        this.planActivationRepo = planActivationRepo;
    }

    /** The live situation for {@code household}, or null when calm. */
    public ActiveResponse resolve(Group household, RiskProfileDto riskProfile, Instant now) {
        if (household == null) return null;

        List<PlanActivation> live = planActivationRepo.findLiveForHousehold(household, now);
        if (live != null && !live.isEmpty() && live.get(0) != null) {
            String id = live.get(0).getId();
            return new ActiveResponse(Kind.PLAN_ACTIVATION, "Your plan is active", WAIT_DETAIL,
                    ReadinessAction.OPEN_ACTIVE_SITUATION,
                    id == null ? Map.of() : Map.of("activationId", id));
        }

        if ("Active".equalsIgnoreCase(household.getAlert())) {
            return new ActiveResponse(Kind.CHECK_IN, "Your household is checking in",
                    "Let your household know how you are. Preparedness steps can wait.",
                    ReadinessAction.OPEN_CHECK_IN,
                    household.getGroupId() == null ? Map.of() : Map.of("householdId", household.getGroupId()));
        }

        if (riskProfile != null) {
            List<ActiveAlertDto> alerts = riskProfile.activeAlerts() == null ? List.of() : riskProfile.activeAlerts();
            boolean upgraded = riskProfile.riskAdjustedRequirements() != null
                    && riskProfile.riskAdjustedRequirements().stream()
                        .anyMatch(r -> r != null && ReadinessCatalog.ORIGIN_ACTIVE_ALERT.equals(r.origin()));
            if (!alerts.isEmpty() || upgraded) {
                String headline = alerts.stream()
                        .filter(a -> a != null && a.headline() != null && !a.headline().isBlank())
                        .map(ActiveAlertDto::headline)
                        .findFirst()
                        .orElse("An official alert is in effect");
                return new ActiveResponse(Kind.OFFICIAL_ALERT, headline,
                        "Follow official instructions first. Preparedness steps can wait.",
                        ReadinessAction.OPEN_ACTIVE_ALERTS, Map.of());
            }
        }
        return null;
    }
}
