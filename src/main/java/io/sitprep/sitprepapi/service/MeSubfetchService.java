package io.sitprep.sitprepapi.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * Per-sub-fetch transactional boundary for {@code MeService.buildMe} /
 * {@code buildMePlans}. Each call to {@link #runReadOnly(Supplier)} starts
 * a fresh REQUIRES_NEW read-only transaction, so if one sub-fetch throws a
 * Hibernate exception (and Spring marks the inner tx rollback-only), the
 * outer {@code buildMe} can still proceed and run the remaining sub-fetches
 * in their own clean transactions.
 *
 * <p>Why a separate bean: {@code @Transactional} on a method called from
 * the same class via {@code this} is bypassed (Spring's proxy is only
 * applied at the bean boundary). Routing through this collaborator
 * guarantees the propagation actually takes effect.</p>
 *
 * <p>Paired with {@code MeService.safeGet}, which catches any exception
 * from the supplier, records the section name in {@link MeBuildContext},
 * and substitutes a caller-provided fallback. One poisoned row no longer
 * sinks the whole /me response, and the FE knows precisely which sections
 * degraded.</p>
 */
@Service
public class MeSubfetchService {

    /**
     * Run {@code op} inside a fresh read-only transaction. The supplier
     * must perform repo reads only — write attempts will fail at flush
     * because the tx is marked read-only.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public <T> T runReadOnly(Supplier<T> op) {
        return op.get();
    }
}
