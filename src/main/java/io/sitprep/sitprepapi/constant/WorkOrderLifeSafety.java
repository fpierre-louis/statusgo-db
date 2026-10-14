package io.sitprep.sitprepapi.constant;

import java.util.List;
import java.util.Map;

/**
 * The ONE definition of a work order's life-safety state (audit TK-11). It
 * was copied into four FE files (GroupTasksPage, WorkOrderDetail,
 * WorkOrderDashboard, taskTriage); the server now ships it as
 * {@code PostDto.lifeSafety}.
 *
 * <p>This is the <b>work-order</b> triage flag (someone trapped, a gas leak,
 * a downed line…), not an official-alert classification: alert cards carry
 * {@code presentation.priority='life_safety'} on their own path.</p>
 */
public final class WorkOrderLifeSafety {

    private WorkOrderLifeSafety() {}

    /** Universal work_details flags that force an emergency escalation. */
    public static final List<String> FLAGS = List.of(
            "occupantTrapped", "medicalPowerDependence", "gasLeakSuspected",
            "downedPowerLine", "structurallyUnsafe", "hazmatSpillPresent",
            "carbonMonoxideRisk");

    /** Any universal flag is true (the server's escalation rule). */
    public static boolean flagged(Map<String, Object> wd) {
        return wd != null && FLAGS.stream().anyMatch(k -> boolTrue(wd.get(k)));
    }

    /**
     * Life-safety for display and triage: a universal flag OR a derived
     * do-not-enter site (the FE {@code isLifeSafety} rule, so the server value
     * and the old client copies agree row for row).
     */
    public static boolean isLifeSafety(Map<String, Object> wd) {
        if (flagged(wd)) return true;
        Object site = wd == null ? null : wd.get("siteSafeToEnter");
        return site != null && "unsafeDoNotEnter".equals(String.valueOf(site).trim());
    }

    private static boolean boolTrue(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return "true".equalsIgnoreCase(s.trim());
        return false;
    }
}
