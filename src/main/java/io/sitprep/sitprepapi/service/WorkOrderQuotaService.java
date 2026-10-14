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
import java.util.function.BooleanSupplier;

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
 * <p>D26 (2026-10-13): life-safety work and alert mode are exempt, and the
 * cap stays a soft warning until billing is live. See {@link #assertQuota}.</p>
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
    private final BillingService billingService;

    public WorkOrderQuotaService(PostRepo taskRepo, BillingService billingService) {
        this.taskRepo = taskRepo;
        this.billingService = billingService;
    }

    /**
     * Assert the group may create another work order under its plan this month.
     *
     * <p>Owner decision D26 (2026-10-13; audit TK-07, PR-01). Never meter
     * safety, and never block before anyone can pay:</p>
     * <ul>
     *   <li>life-safety work ({@code lifeSafety}) is never metered;</li>
     *   <li>a group in alert ({@code alert == "Active"}) is never metered;</li>
     *   <li>a work order whose location sits in an area in alert or crisis
     *       ({@code areaInAlert}) is never metered. The supplier is only
     *       asked once the cap is actually reached, because resolving the
     *       area may geocode;</li>
     *   <li>the cap reads the EFFECTIVE tier
     *       ({@link BillingService#effectivePlanTier}, B1's one resolver) and
     *       the cap table on {@link PlanTier#workOrdersPerMonth()};</li>
     *   <li>while billing is not live ({@link BillingService#isConfigured()}
     *       false) the cap is a soft warning: logged, never a 402. The 402
     *       returns the day Stripe is configured.</li>
     * </ul>
     *
     * @param group       the owning group of the work order being created
     * @param lifeSafety  the server-derived life-safety flag of the new row
     * @param areaInAlert lazily answers "is this work order's location in an
     *                    area in alert?"; may be null
     * @throws QuotaExceededException (→ 402) only when billing is live and
     *         the group is over its monthly cap with no exemption
     */
    public void assertQuota(Group group, boolean lifeSafety, BooleanSupplier areaInAlert) {
        if (group == null) return; // personal/community task — never metered
        if (lifeSafety) return;
        if ("Active".equalsIgnoreCase(group.getAlert())) return;

        PlanTier tier = BillingService.effectivePlanTier(group);
        Integer cap = tier.workOrdersPerMonth();
        if (cap == null) {
            return; // AGENCY / PREMIUM_AGENCY — unlimited, metered for reporting only
        }

        Instant since = startOfCurrentMonthUtc();
        long used = taskRepo.countByGroupIdAndKindAndCreatedAtGreaterThanEqual(
                group.getGroupId(), WORK_ORDER_KIND, since);
        if (used < cap) return;

        if (areaInAlert != null && safeAsk(areaInAlert)) {
            log.info("Work-order quota exempt (area in alert): group={} tier={} cap={} used={}",
                    group.getGroupId(), tier, cap, used);
            return;
        }
        if (!billingService.isConfigured()) {
            log.info("Work-order quota over cap, soft until billing is live: group={} tier={} cap={} used={}",
                    group.getGroupId(), tier, cap, used);
            return;
        }
        log.info("Work-order quota hit: group={} tier={} cap={} used={}",
                group.getGroupId(), tier, cap, used);
        throw new QuotaExceededException(group.getGroupId(), tier, cap, used);
    }

    private static boolean safeAsk(BooleanSupplier s) {
        try {
            return s.getAsBoolean();
        } catch (RuntimeException e) {
            log.debug("Work-order quota: area-alert lookup failed: {}", e.getMessage());
            return false;
        }
    }

    /** First instant of the current calendar month, UTC — the metering window. */
    private Instant startOfCurrentMonthUtc() {
        return YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
