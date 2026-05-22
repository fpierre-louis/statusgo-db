package io.sitprep.sitprepapi.bootstrap;

import io.sitprep.sitprepapi.service.HouseholdBackfillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs the household-ownership backfill once on each boot
 * (docs/WIP_HOUSEHOLD_PLANS.md, Phase 1). Schema columns are added by
 * Hibernate {@code ddl-auto: update} during EntityManagerFactory init,
 * which completes before any ApplicationRunner fires — so the new
 * columns exist by the time this runs.
 *
 * <p>Any failure is swallowed (logged, not rethrown) so a backfill hiccup
 * never blocks application startup; the work is idempotent and retries on
 * the next boot. DELETE this class with {@link HouseholdBackfillService}
 * once the migration is universally applied.</p>
 */
@Component
public class HouseholdBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HouseholdBackfillRunner.class);

    private final HouseholdBackfillService backfill;

    public HouseholdBackfillRunner(HouseholdBackfillService backfill) {
        this.backfill = backfill;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            backfill.run();
        } catch (Exception e) {
            log.error("Household backfill failed; will retry next boot. cause={}", e.getMessage(), e);
        }
    }
}
