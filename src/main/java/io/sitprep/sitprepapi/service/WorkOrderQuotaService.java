package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.PlanTier;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.exception.QuotaExceededException;
import io.sitprep.sitprepapi.repo.PostRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Metered-monetization guard for group/agency work-order creation.
 *
 * <p>Mirrors {@code GroupService.assertSeatAvailable} (which throws 409 when a
 * paid seat cap is full) — one axis over: this throws
 * {@link QuotaExceededException} (HTTP 402 Payment Required) when a group has
 * exhausted its monthly work-order allowance, so the client can distinguish
 * "you've hit your plan, upgrade" from a capacity conflict and open the Stripe
 * Checkout flow. See {@code DOCS_GROWTH_MONETIZATION.md} §1.5.</p>
 *
 * <p>Called from {@code PostService.create(...)} for group-scoped {@code
 * kind="task"} rows only. Personal preparedness tasks (groupId null) and
 * resident civic-report filings are never metered and must not be routed here
 * (monetization-integrity — never meter safety).</p>
 */
@Service
public class WorkOrderQuotaService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderQuotaService.class);

    /** Only operational work orders (kind="task") count against the allowance. */
    private static final String WORK_ORDER_KIND = "task";

    private final PostRepo taskRepo;

    public WorkOrderQuotaService(PostRepo taskRepo) {
        this.taskRepo = taskRepo;
    }

    /**
     * Assert the group may create another work order under its plan this month.
     *
     * @param group the owning group of the work order being created
     * @throws QuotaExceededException (→ 402) when the group is over its monthly
     *         work-order cap for its tier
     */
    public void assertQuota(Group group) {
        if (group == null) return; // personal/community task — never metered

        // Honors a stored tier today; a time-boxed admin override (Group
        // .subscriptionOverrideTier) can be folded in here without touching
        // call sites when Phase 3 wires override-expiry through BillingService.
        PlanTier tier = PlanTier.fromWire(group.getPlanTier());

        Integer cap = workOrderCapFor(tier);
        if (cap == null) {
            return; // AGENCY / PREMIUM_AGENCY — unlimited, metered for reporting only
        }

        Instant since = startOfCurrentMonthUtc();
        long used = taskRepo.countByGroupIdAndKindAndCreatedAtGreaterThanEqual(
                group.getGroupId(), WORK_ORDER_KIND, since);

        if (used >= cap) {
            log.info("Work-order quota hit: group={} tier={} cap={} used={}",
                    group.getGroupId(), tier, cap, used);
            throw new QuotaExceededException(group.getGroupId(), tier, cap, used);
        }
    }

    /**
     * Monthly operational work-order allowance per tier. {@code null} = unlimited.
     * Illustrative Phase 2 values; final numbers are a pricing decision and will
     * move onto {@link PlanTier} itself so they live in one place.
     */
    private Integer workOrderCapFor(PlanTier tier) {
        return switch (tier) {
            case FREE -> 15;
            case GROUP -> 200;
            case BUSINESS -> 2000;
            case AGENCY, PREMIUM_AGENCY -> null; // unlimited
        };
    }

    /** First instant of the current calendar month, UTC — the metering window. */
    private Instant startOfCurrentMonthUtc() {
        return YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
