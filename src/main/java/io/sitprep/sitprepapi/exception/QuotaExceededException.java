package io.sitprep.sitprepapi.exception;

import io.sitprep.sitprepapi.constant.PlanTier;

/**
 * Thrown when a group has exhausted its monthly work-order allowance under its
 * current plan tier. Mapped to HTTP 402 Payment Required by
 * {@link GlobalExceptionHandler} so the client can distinguish a plan limit
 * (upgrade to continue) from a capacity conflict (409) — and open the Stripe
 * Checkout flow. See {@code DOCS_GROWTH_MONETIZATION.md} §1.
 */
public class QuotaExceededException extends RuntimeException {

    private final String groupId;
    private final PlanTier tier;
    private final int cap;
    private final long used;

    public QuotaExceededException(String groupId, PlanTier tier, int cap, long used) {
        super("This plan (" + tier.label() + ") allows " + cap
                + " work orders per month and " + used
                + " have been created. Upgrade your plan to create more.");
        this.groupId = groupId;
        this.tier = tier;
        this.cap = cap;
        this.used = used;
    }

    public String getGroupId() { return groupId; }
    public PlanTier getTier() { return tier; }
    public int getCap() { return cap; }
    public long getUsed() { return used; }
}
