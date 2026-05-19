package io.sitprep.sitprepapi.constant;

/**
 * Discrete capabilities an organization plan can unlock. Each
 * {@link PlanTier} carries a cumulative set of these; a gated feature
 * asks {@code PlanTier.has(capability)}.
 *
 * <p>Phase 4 ("org plans go live"). Enforcement is <b>soft</b> at
 * launch — the model + checks ship, but no endpoint throws on a
 * missing capability until Stripe billing is live. The frontend reads
 * the tier to render plan badges + upgrade affordances; nothing is
 * blocked yet. When billing lands, the same {@code has(...)} checks
 * become hard 402/403 gates.</p>
 */
public enum PlanCapability {
    /** Assign a group work-order to a specific member. */
    TASK_ASSIGNMENT,
    /** Org admin dashboard — readiness rollup, roster, task board. */
    ADMIN_DASHBOARD,
    /** Export the member roster as CSV. */
    ROSTER_EXPORT,
    /** Custom org logo / co-branded group page. */
    CO_BRANDED_PAGE,
    /** Announcements with priority push. */
    PRIORITY_PUSH,
    /** Department / sub-group structure. */
    DEPARTMENT_SUBGROUPS,
    /** Audit-log export. */
    AUDIT_LOG_EXPORT,
    /** Granular Owner / Admin / Member role control. */
    GRANULAR_RBAC,
    /** Verified-agency geo-targeted alerts (Phase 5). */
    AGENCY_ALERTS,
    /** Aggregate readiness dashboard for emergency managers (Phase 5). */
    AGGREGATE_READINESS_DASHBOARD
}
