package io.sitprep.sitprepapi.readiness;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import org.springframework.data.domain.PageRequest;

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

    // ------------------------------------------------------------------ V99 reminder sweep

    private HouseholdReadinessItemState remind(String hh, String email, String remindAt) {
        HouseholdReadinessItemState r = row(hh, "outage.charge_plan", "USER", email, ItemStateKind.REMIND_LATER);
        r.setRemindAt(Instant.parse(remindAt));
        return repo.save(r);
    }

    @Test
    void dueRemindersAreOnlyUnhandledRemindLaterRowsAtOrBeforeNow_keysetById() {
        Instant now = Instant.parse("2026-10-20T12:00:00Z");
        HouseholdReadinessItemState dueA = remind("hh-1", "a@example.com", "2026-10-14T12:00:00Z");
        HouseholdReadinessItemState dueExactlyNow = remind("hh-1", "b@example.com", "2026-10-20T12:00:00Z");
        remind("hh-1", "c@example.com", "2026-10-21T12:00:00Z");                     // not due yet
        HouseholdReadinessItemState handled = remind("hh-2", "a@example.com", "2026-10-01T12:00:00Z");
        handled.setRemindedAt(Instant.parse("2026-10-01T12:05:00Z"));
        repo.save(handled);                                                            // already reminded
        row("hh-3", "outage.charge_plan", "USER", "a@example.com", ItemStateKind.SKIPPED); // not a reminder
        em.flush();
        em.clear();

        List<HouseholdReadinessItemState> due = repo.findDueReminders(ItemStateKind.REMIND_LATER, now, 0, PageRequest.of(0, 10));
        assertThat(due).extracting(HouseholdReadinessItemState::getId)
                .containsExactly(dueA.getId(), dueExactlyNow.getId());

        // Keyset: a page of one, then everything after the last id seen.
        assertThat(repo.findDueReminders(ItemStateKind.REMIND_LATER, now, 0, PageRequest.of(0, 1)))
                .extracting(HouseholdReadinessItemState::getId).containsExactly(dueA.getId());
        assertThat(repo.findDueReminders(ItemStateKind.REMIND_LATER, now, dueA.getId(), PageRequest.of(0, 1)))
                .extracting(HouseholdReadinessItemState::getId).containsExactly(dueExactlyNow.getId());
        assertThat(repo.findDueReminders(ItemStateKind.REMIND_LATER, now, dueExactlyNow.getId(), PageRequest.of(0, 1)))
                .isEmpty();
    }

    @Test
    void claimStampsOnceAndRefusesAChangedSnooze() {
        Instant now = Instant.parse("2026-10-20T12:00:00Z");
        HouseholdReadinessItemState r = remind("hh-1", "a@example.com", "2026-10-14T12:00:00Z");
        em.flush();
        em.clear();

        // A stale remindAt (the member re-snoozed after the sweep read the row) is refused.
        assertThat(repo.claimReminder(r.getId(), ItemStateKind.REMIND_LATER, Instant.parse("2026-10-13T12:00:00Z"), now))
                .isZero();
        assertThat(repo.claimReminder(r.getId(), ItemStateKind.REMIND_LATER, r.getRemindAt(), now)).isEqualTo(1);
        // Second claim (another instance, or the next sweep) loses.
        assertThat(repo.claimReminder(r.getId(), ItemStateKind.REMIND_LATER, r.getRemindAt(), now)).isZero();
        em.clear();
        assertThat(repo.findById(r.getId()).orElseThrow().getRemindedAt()).isEqualTo(now);
        assertThat(repo.findDueReminders(ItemStateKind.REMIND_LATER, now, 0, PageRequest.of(0, 10))).isEmpty();
    }
}
