package io.sitprep.sitprepapi.constant;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Organization plan tiers — the paid ladder above the free safety
 * experience. See {@code docs/BUSINESS_MODEL.md} "Pricing ladder".
 *
 * <p>Personal / household plans (Free, Family) live on
 * {@code UserInfo.subscription}; THIS enum is the org/group axis,
 * stored as the enum name on {@code Group.planTier}.</p>
 *
 * <p>Tiers are cumulative by {@link #rank()} — a higher tier unlocks
 * everything the tiers below it do, plus its own additions.</p>
 *
 * <p>Phase 4. Enforcement is soft at launch — see {@link PlanCapability}.</p>
 */
public enum PlanTier {

    FREE(0, "Free", "$0"),

    GROUP(1, "Group", "from $19/mo",
            PlanCapability.TASK_ASSIGNMENT,
            PlanCapability.ADMIN_DASHBOARD,
            PlanCapability.ROSTER_EXPORT,
            PlanCapability.CO_BRANDED_PAGE,
            PlanCapability.PRIORITY_PUSH),

    BUSINESS(2, "Business", "from $199/mo",
            PlanCapability.DEPARTMENT_SUBGROUPS,
            PlanCapability.AUDIT_LOG_EXPORT,
            PlanCapability.GRANULAR_RBAC),

    AGENCY(3, "Agency", "from $15k/yr",
            PlanCapability.AGENCY_ALERTS,
            PlanCapability.AGGREGATE_READINESS_DASHBOARD),

    PREMIUM_AGENCY(4, "Premium Agency", "Custom");

    private final int rank;
    private final String label;
    private final String priceHint;
    private final Set<PlanCapability> addedCapabilities;

    PlanTier(int rank, String label, String priceHint, PlanCapability... added) {
        this.rank = rank;
        this.label = label;
        this.priceHint = priceHint;
        this.addedCapabilities = added.length == 0
                ? EnumSet.noneOf(PlanCapability.class)
                : EnumSet.copyOf(Arrays.asList(added));
    }

    public int rank() { return rank; }

    public String label() { return label; }

    /** Informational price hint for the self-serve plan picker. */
    public String priceHint() { return priceHint; }

    /** Cumulative capability set — own additions plus every lower tier's. */
    public Set<PlanCapability> capabilities() {
        EnumSet<PlanCapability> all = EnumSet.noneOf(PlanCapability.class);
        for (PlanTier t : values()) {
            if (t.rank <= this.rank) all.addAll(t.addedCapabilities);
        }
        return Collections.unmodifiableSet(all);
    }

    public boolean has(PlanCapability capability) {
        return capability != null && capabilities().contains(capability);
    }

    public boolean isAtLeast(PlanTier other) {
        return other != null && this.rank >= other.rank;
    }

    /**
     * Parse a wire / DB value to a tier. Null, blank, or unrecognized
     * input falls back to {@link #FREE} — legacy {@code Group} rows have
     * a null {@code plan_tier} column and must read as the free tier.
     */
    public static PlanTier fromWire(String s) {
        if (s == null || s.isBlank()) return FREE;
        try {
            return PlanTier.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return FREE;
        }
    }
}
