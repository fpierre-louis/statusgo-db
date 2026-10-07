package io.sitprep.sitprepapi.readiness;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HouseholdReadinessItemState} + its repo against a real ORM (H2,
 * Hibernate-built DDL, {@code test} profile): the mapping persists, the
 * enum round-trips as its name, the timestamps are filled, and the three
 * finders the journey service relies on select the right rows.
 *
 * <p>What H2 can NOT prove — the partial unique indexes and the CHECKs — was
 * verified against a scratch local Postgres with the real V98 SQL
 * (EXEC-B1.md, trap T-2).</p>
 */
@DataJpaTest
@ActiveProfiles("test")
class HouseholdReadinessItemStateRepoTest {

    @Autowired
    private HouseholdReadinessItemStateRepo repo;

    @Autowired
    private TestEntityManager em;

    private HouseholdReadinessItemState row(String hh, String key, String scope, String email, ItemStateKind state) {
        HouseholdReadinessItemState r = new HouseholdReadinessItemState();
        r.setHouseholdId(hh);
        r.setItemKey(key);
        r.setScope(scope);
        r.setUserEmail(email);
        r.setState(state);
        r.setCreatedBy(email == null ? "admin@example.com" : email);
        if (state == ItemStateKind.SKIPPED) r.setSuppressedUntil(Instant.parse("2026-11-06T12:00:00Z"));
        if (state == ItemStateKind.REMIND_LATER) r.setRemindAt(Instant.parse("2026-10-14T12:00:00Z"));
        return repo.save(r);
    }

    @Test
    void mapsAndRoundTripsEveryColumn() {
        HouseholdReadinessItemState saved = row("hh-1", "documents.first_folder", "HOUSEHOLD", null, ItemStateKind.DONE);
        saved.setReasonCode("printed");
        repo.saveAndFlush(saved);
        em.clear();

        HouseholdReadinessItemState read = repo.findById(saved.getId()).orElseThrow();
        assertThat(read.getHouseholdId()).isEqualTo("hh-1");
        assertThat(read.getItemKey()).isEqualTo("documents.first_folder");
        assertThat(read.getScope()).isEqualTo(HouseholdReadinessItemState.SCOPE_HOUSEHOLD);
        assertThat(read.getUserEmail()).isNull();
        assertThat(read.getState()).isEqualTo(ItemStateKind.DONE);
        assertThat(read.getReasonCode()).isEqualTo("printed");
        assertThat(read.getCreatedAt()).isNotNull();
        assertThat(read.getUpdatedAt()).isNotNull();

        // The enum is stored as its name — the V98 CHECK lists names, not ordinals.
        Object stored = em.getEntityManager()
                .createNativeQuery("SELECT state FROM household_readiness_item_state WHERE id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();
        assertThat(stored).hasToString("DONE");
    }

    @Test
    void findersSelectByScopeAndUser() {
        row("hh-1", "documents.first_folder", "HOUSEHOLD", null, ItemStateKind.NOT_RELEVANT);
        row("hh-1", "documents.first_folder", "USER", "a@example.com", ItemStateKind.SKIPPED);
        row("hh-1", "documents.first_folder", "USER", "b@example.com", ItemStateKind.REMIND_LATER);
        row("hh-2", "documents.first_folder", "HOUSEHOLD", null, ItemStateKind.DONE);
        em.flush();
        em.clear();

        List<HouseholdReadinessItemState> all = repo.findByHouseholdId("hh-1");
        assertThat(all).hasSize(3).allSatisfy(r -> assertThat(r.getHouseholdId()).isEqualTo("hh-1"));

        assertThat(repo.findFirstByHouseholdIdAndItemKeyAndScope("hh-1", "documents.first_folder", "HOUSEHOLD"))
                .hasValueSatisfying(r -> assertThat(r.getState()).isEqualTo(ItemStateKind.NOT_RELEVANT));

        assertThat(repo.findFirstByHouseholdIdAndItemKeyAndScopeAndUserEmail(
                "hh-1", "documents.first_folder", "USER", "b@example.com"))
                .hasValueSatisfying(r -> {
                    assertThat(r.getState()).isEqualTo(ItemStateKind.REMIND_LATER);
                    assertThat(r.getRemindAt()).isEqualTo(Instant.parse("2026-10-14T12:00:00Z"));
                });

        assertThat(repo.findFirstByHouseholdIdAndItemKeyAndScopeAndUserEmail(
                "hh-1", "documents.first_folder", "USER", "c@example.com")).isEmpty();
        assertThat(repo.findFirstByHouseholdIdAndItemKeyAndScope("hh-1", "outage.charge_plan", "HOUSEHOLD")).isEmpty();
    }
}
