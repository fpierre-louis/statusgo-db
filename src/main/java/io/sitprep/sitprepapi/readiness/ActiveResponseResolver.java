package io.sitprep.sitprepapi.readiness;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.ActiveAlertDto;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskProfileDto;
import io.sitprep.sitprepapi.repo.PlanActivationRepo;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Gate 1 of the readiness journey (CONTRACT.md §3b): has this household
 * started a response? When it has, preparedness steps wait — no next step,
 * no reminders, no commerce tools.
 *
 * <p>First match wins: a live plan activation, then an open household
 * check-in. Those are the two things a person <i>activates</i>.</p>
 *
 * <p><b>An official alert at home is not Gate 1</b> (owner, 2026-10-10,
 * EXEC-H1). It used to be, and it took Ready for More and Practice away from
 * someone under a Flood Watch who had come looking for exactly that. An alert
 * now yields an {@link AlertHeadsUp}: the steps stay, and a quiet card asks
 * whether everyone is okay and ready to act. Commerce and reminders stay quiet
 * under it — those are proactive, the ruling is about not removing options.</p>
 */
@Component
public class ActiveResponseResolver {

    public enum Kind { PLAN_ACTIVATION, CHECK_IN }

    public record ActiveResponse(Kind kind, String title, String detail,
                                 ReadinessAction action, Map<String, String> params) {}

    /** An official alert at home, said as a nudge. The surface keeps its content. */
    public record AlertHeadsUp(String title, String detail, ReadinessAction action, Map<String, String> params) {}

    /** Which surface the nudge sits on; only the permission sentence differs. */
    public enum Surface {
        READY_FOR_MORE("Just here for tips? Keep going."),
        PRACTICE("Just here to practice? Keep going.");

        final String keepGoing;
        Surface(String keepGoing) { this.keepGoing = keepGoing; }
    }

    static final String HEADS_UP_CHECK =
            "Now's a good time to check that everyone's okay and ready to leave or act if needed.";
    static final String HEADS_UP_FALLBACK_TITLE = "An alert is in effect near home";

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

        return null;
    }

    /**
     * The nudge for an official alert at home, or null when there is none.
     * Names the most severe alert by its product name ("Flood Watch near
     * home"); a precaution with no alert row, or a source without a product
     * name, gets the plain title.
     */
    public static AlertHeadsUp alertHeadsUp(RiskProfileDto riskProfile, Surface surface) {
        if (!hasOfficialAlert(riskProfile)) return null;
        List<ActiveAlertDto> alerts = riskProfile.activeAlerts() == null ? List.of() : riskProfile.activeAlerts();
        String title = alerts.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(ActiveResponseResolver::severityRank))
                .map(ActiveAlertDto::event)
                .filter(e -> e != null && !e.isBlank())
                .findFirst()
                .map(e -> e.trim() + " near home")
                .orElse(HEADS_UP_FALLBACK_TITLE);
        Surface on = surface == null ? Surface.READY_FOR_MORE : surface;
        return new AlertHeadsUp(title, HEADS_UP_CHECK + " " + on.keepGoing,
                ReadinessAction.OPEN_ACTIVE_ALERTS, Map.of());
    }

    /** Extreme first; unknown last. */
    private static int severityRank(ActiveAlertDto a) {
        String s = a.severity() == null ? "" : a.severity().trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "extreme" -> 0;
            case "severe" -> 1;
            case "moderate" -> 2;
            case "minor" -> 3;
            default -> 4;
        };
    }

    /**
     * An official alert at the household's home: an active alert in the risk
     * profile, or an {@code active_alert_upgraded} precaution. Shared with
     * {@link io.sitprep.sitprepapi.service.CommerceSuppressionService} so the
     * journey's active response and commerce suppression cannot disagree.
     */
    public static boolean hasOfficialAlert(RiskProfileDto riskProfile) {
        if (riskProfile == null) return false;
        if (riskProfile.activeAlerts() != null && !riskProfile.activeAlerts().isEmpty()) return true;
        return riskProfile.riskAdjustedRequirements() != null
                && riskProfile.riskAdjustedRequirements().stream()
                    .anyMatch(r -> r != null && ReadinessCatalog.ORIGIN_ACTIVE_ALERT.equals(r.origin()));
    }
}
