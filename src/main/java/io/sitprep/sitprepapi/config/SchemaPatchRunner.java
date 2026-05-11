package io.sitprep.sitprepapi.config;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * One-shot schema patches that JPA's {@code ddl-auto: update} mode
 * doesn't apply automatically. Hibernate's {@code update} adds new
 * columns and tables but never drops {@code NOT NULL} (or any
 * constraint) on an existing column — by design, to avoid data loss
 * on an unattended migration. When we relax a column from required
 * to optional in the entity, the live DB still rejects the now-valid
 * null inserts until someone runs the matching ALTER.
 *
 * <p>This runner applies those one-time patches at boot, idempotently
 * (each statement is safe to re-run because Postgres' {@code DROP NOT
 * NULL} on an already-nullable column is a no-op). It logs each
 * applied patch so the boot transcript shows exactly which schema
 * deltas landed.</p>
 *
 * <p>Add new patches inline as the entity changes. Patches are
 * intentionally NOT a full migration framework (no version tracking) —
 * they're meant for narrow "we already shipped this change everywhere
 * except the DB constraint" cases. Anything beyond column nullability
 * tweaks should land via Flyway/Liquibase instead.</p>
 */
@Component
public class SchemaPatchRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaPatchRunner.class);

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public void run(String... args) {
        // 2026-05-04 — Post (task) title became nullable for body-only
        // kinds (post / tip). Entity already declares it nullable; this
        // drops the legacy NOT NULL on existing databases. Postgres
        // returns silently when the constraint is already absent, so
        // this is safe to run on every boot.
        runQuietly("ALTER TABLE task ALTER COLUMN title DROP NOT NULL",
                "task.title is now nullable (body-only post kinds)");
    }

    private void runQuietly(String sql, String description) {
        try {
            em.createNativeQuery(sql).executeUpdate();
            log.info("Schema patch applied: {}", description);
        } catch (Exception e) {
            // Not fatal — log and continue. A missing table on a fresh
            // DB just means Hibernate will create it with the correct
            // shape on the same boot.
            log.warn("Schema patch skipped ({}): {}", description, e.getMessage());
        }
    }
}
