package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.readiness.ReadinessAction;

import java.util.EnumSet;
import java.util.Set;

/**
 * Which semantic actions a Practice debrief may offer as its "next family
 * step". Reuses {@link ReadinessAction} so the FE keeps ONE action → route map
 * ({@code readinessActions.js}).
 *
 * <p>Every action here opens a real <b>preparedness editor</b> where the adult
 * decides whether to change anything. The emergency surfaces —
 * {@code OPEN_ACTIVE_SITUATION}, {@code OPEN_CHECK_IN},
 * {@code OPEN_ACTIVE_ALERTS} — are deliberately absent: a rehearsal must never
 * hand the user into live emergency state (the Practice invariant).</p>
 */
public final class PracticeActions {

    private PracticeActions() {}

    public static final Set<ReadinessAction> ALLOWED = EnumSet.of(
            ReadinessAction.OPEN_EMERGENCY_CONTACTS,
            ReadinessAction.OPEN_EVACUATION_PLAN,
            ReadinessAction.OPEN_HOUSEHOLD_PLAN,
            ReadinessAction.OPEN_HOME_STOCKPILE,
            ReadinessAction.OPEN_GO_BAG,
            ReadinessAction.OPEN_ALERT_PREFERENCES,
            ReadinessAction.OPEN_DRILL);

    public static boolean allowed(ReadinessAction action) {
        return action != null && ALLOWED.contains(action);
    }
}
