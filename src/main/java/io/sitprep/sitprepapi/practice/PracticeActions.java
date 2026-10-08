package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.readiness.ReadinessAction;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Which semantic actions a Practice debrief may offer as its "next family
 * step", and how that step is worded. Reuses {@link ReadinessAction} so the FE
 * keeps ONE action → route map ({@code readinessActions.js}).
 *
 * <p>Every action here opens a real <b>preparedness editor</b> where the adult
 * decides whether to change anything. The emergency surfaces —
 * {@code OPEN_ACTIVE_SITUATION}, {@code OPEN_CHECK_IN},
 * {@code OPEN_ACTIVE_ALERTS} — are deliberately absent: a rehearsal must never
 * hand the user into live emergency state (the Practice invariant).</p>
 *
 * <p>The wording is the server's (backend shapes, FE displays). {@code note}
 * states the Practice/live boundary at the moment the user leaves Practice:
 * opening the editor changes nothing until they save.</p>
 */
public final class PracticeActions {

    private PracticeActions() {}

    /** The next-step card's copy for one action. */
    public record StepCopy(String prompt, String label, String note) {}

    private static final Map<ReadinessAction, StepCopy> COPY = new EnumMap<>(ReadinessAction.class);

    static {
        COPY.put(ReadinessAction.OPEN_EMERGENCY_CONTACTS, new StepCopy(
                "Look over your emergency contacts together.",
                "Review emergency contacts",
                "Opens your real contacts. Nothing changes unless you save it."));
        COPY.put(ReadinessAction.OPEN_EVACUATION_PLAN, new StepCopy(
                "Check that everyone knows your meeting places.",
                "Review meeting places",
                "Opens your real evacuation plan. Nothing changes unless you save it."));
        COPY.put(ReadinessAction.OPEN_HOUSEHOLD_PLAN, new StepCopy(
                "Read your household plan together.",
                "Open your household plan",
                "Opens your real plan. Nothing changes unless you edit it."));
        COPY.put(ReadinessAction.OPEN_HOME_STOCKPILE, new StepCopy(
                "Check your home kit together.",
                "Review your home kit",
                "Opens your real home kit. Nothing changes unless you save it."));
        COPY.put(ReadinessAction.OPEN_GO_BAG, new StepCopy(
                "Check your go-bag together.",
                "Review your go-bag",
                "Opens your real go-bag. Nothing changes unless you save it."));
        COPY.put(ReadinessAction.OPEN_ALERT_PREFERENCES, new StepCopy(
                "Make sure official alerts can reach everyone.",
                "Review alert settings",
                "Opens your real alert settings. Nothing changes unless you save it."));
        COPY.put(ReadinessAction.OPEN_DRILL, new StepCopy(
                "Try the matching drill together.",
                "Open the drill",
                "Opens a household drill. Nothing is recorded unless you mark it done."));
    }

    public static final Set<ReadinessAction> ALLOWED = EnumSet.copyOf(COPY.keySet());

    public static boolean allowed(ReadinessAction action) {
        return action != null && ALLOWED.contains(action);
    }

    public static StepCopy describe(ReadinessAction action) {
        return action == null ? null : COPY.get(action);
    }
}
