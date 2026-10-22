package io.sitprep.sitprepapi.practice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 12-month sweep keeps the newest COMPLETED run per scope and the newest
 * family completion per activity, however old, and never touches IN_PROGRESS.
 */
@DataJpaTest
@ActiveProfiles("test")
class PracticeRetentionSweepTest {

    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
    private static final Instant OLD = Instant.parse("2025-06-01T12:00:00Z");   // > 12 months
    private static final Instant OLDER = Instant.parse("2025-03-01T12:00:00Z");
    private static final Instant RECENT = Instant.parse("2026-09-01T12:00:00Z");

    @Autowired ScenarioRunRepo runRepo;
    @Autowired FamilyPracticeCompletionRepo completionRepo;
    @Autowired PracticeCheckInAnswerRepo answerRepo;
    @Autowired PracticeSkillStateRepo stateRepo;
    @Autowired TestEntityManager em;

    private PracticeRetentionSweepService sweep;

    @BeforeEach
    void setUp() {
        sweep = new PracticeRetentionSweepService(runRepo, completionRepo, answerRepo, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** The bulk delete bypasses the persistence context, so drop it before asserting on findById. */
    private int sweepAndDetach() {
        int removed = sweep.sweepAll();
        em.clear();
        return removed;
    }

    private ScenarioRun run(String hh, String email, String key, ScenarioRun.Status status, Instant at) {
        ScenarioRun r = new ScenarioRun();
        r.setHouseholdId(hh);
        r.setUserEmail(email);
        r.setScenarioKey(key);
        r.setContentVersion(1);
        r.setContentHash("sha256:abc");
        r.setStatus(status);
        r.setStartedAt(at);
        r.setUpdatedAt(at);
        if (status == ScenarioRun.Status.COMPLETED) r.setCompletedAt(at);
        if (status == ScenarioRun.Status.ABANDONED) r.setAbandonedAt(at);
        return runRepo.saveAndFlush(r);
    }

    private FamilyPracticeCompletion family(String hh, String key, int version, Instant at) {
        FamilyPracticeCompletion c = new FamilyPracticeCompletion();
        c.setHouseholdId(hh);
        c.setActivityKey(key);
        c.setContentVersion(version);
        c.setContentHash("sha256:" + version);
        c.setCompletedAt(at);
        return completionRepo.saveAndFlush(c);
    }

    private PracticeCheckInAnswer answer(String hh, String skill, Instant at) {
        PracticeCheckInAnswer a = new PracticeCheckInAnswer();
        a.setHouseholdId(hh);
        a.setSkillKey(skill);
        a.setCheckinKey("checkin-" + skill);
        a.setContentVersion(1);
        a.setCorrect(true);
        a.setAnsweredAt(at);
        return answerRepo.saveAndFlush(a);
    }

    @Test
    void checkInAnswersPastTheWindowGoButSkillStateIsKept() {
        PracticeCheckInAnswer old = answer("hh-1", "sk", OLD);
        PracticeCheckInAnswer older = answer("hh-1", "sk", OLDER);
        PracticeCheckInAnswer recent = answer("hh-1", "sk", RECENT);
        PracticeSkillState st = new PracticeSkillState();
        st.setHouseholdId("hh-1");
        st.setSkillKey("sk");
        st.setLastSeenAt(OLD);
        st.setIntervalDays(90);
        st.setPriorIntervalDays(30);
        st.setNextDueAt(OLD.plusSeconds(90L * 86400));
        st.setLastResult("CORRECT");
        st.setUpdatedAt(OLD);
        st = stateRepo.saveAndFlush(st);

        int removed = sweepAndDetach();

        assertThat(removed).isEqualTo(2);
        assertThat(answerRepo.findById(old.getId())).isEmpty();
        assertThat(answerRepo.findById(older.getId())).isEmpty();
        assertThat(answerRepo.findById(recent.getId())).isPresent();
        assertThat(stateRepo.findById(st.getId())).as("the schedule outlives the history").isPresent();
    }

    @Test
    void keepsTheNewestCompletedRunPerHouseholdScenarioEvenWhenOld() {
        ScenarioRun older = run("hh-1", "a@x.com", "s1", ScenarioRun.Status.COMPLETED, OLDER);
        ScenarioRun newestOld = run("hh-1", "b@x.com", "s1", ScenarioRun.Status.COMPLETED, OLD);
        ScenarioRun abandonedOld = run("hh-1", "a@x.com", "s1", ScenarioRun.Status.ABANDONED, OLDER);
        ScenarioRun otherScenario = run("hh-1", "a@x.com", "s2", ScenarioRun.Status.COMPLETED, OLDER);
        ScenarioRun otherHousehold = run("hh-2", "a@x.com", "s1", ScenarioRun.Status.COMPLETED, OLDER);

        int removed = sweepAndDetach();

        assertThat(removed).isEqualTo(2);
        assertThat(runRepo.findById(older.getId())).isEmpty();
        assertThat(runRepo.findById(abandonedOld.getId())).isEmpty();
        assertThat(runRepo.findById(newestOld.getId())).isPresent();
        assertThat(runRepo.findById(otherScenario.getId())).isPresent();
        assertThat(runRepo.findById(otherHousehold.getId())).isPresent();
    }

    @Test
    void anOldRunIsDroppedOnceANewerCompletedRunExists_butRecentRowsStay() {
        ScenarioRun old = run("hh-1", "a@x.com", "s1", ScenarioRun.Status.COMPLETED, OLD);
        ScenarioRun recent = run("hh-1", "a@x.com", "s1", ScenarioRun.Status.COMPLETED, RECENT);
        ScenarioRun recentAbandoned = run("hh-1", "a@x.com", "s1", ScenarioRun.Status.ABANDONED, RECENT);

        sweepAndDetach();

        assertThat(runRepo.findById(old.getId())).isEmpty();
        assertThat(runRepo.findById(recent.getId())).isPresent();
        assertThat(runRepo.findById(recentAbandoned.getId())).isPresent();
    }

    @Test
    void neverTouchesAnInProgressRunHoweverOld() {
        ScenarioRun inProgress = run("hh-1", "a@x.com", "s1", ScenarioRun.Status.IN_PROGRESS, OLDER);

        assertThat(sweepAndDetach()).isZero();
        assertThat(runRepo.findById(inProgress.getId())).isPresent();
    }

    @Test
    void personalRunsKeepTheirNewestCompletedPerUserAndScenario() {
        ScenarioRun aOld = run(null, "a@x.com", "s1", ScenarioRun.Status.COMPLETED, OLDER);
        ScenarioRun aNewest = run(null, "a@x.com", "s1", ScenarioRun.Status.COMPLETED, OLD);
        ScenarioRun bOnly = run(null, "b@x.com", "s1", ScenarioRun.Status.COMPLETED, OLDER);

        sweepAndDetach();

        assertThat(runRepo.findById(aOld.getId())).isEmpty();
        assertThat(runRepo.findById(aNewest.getId())).isPresent();
        assertThat(runRepo.findById(bOnly.getId())).isPresent();
    }

    @Test
    void familyCompletionsKeepTheNewestPerHouseholdActivity() {
        FamilyPracticeCompletion v1 = family("hh-1", "kit-builder", 1, OLDER);
        FamilyPracticeCompletion v2 = family("hh-1", "kit-builder", 2, OLD);
        FamilyPracticeCompletion recent = family("hh-1", "kit-builder", 3, RECENT);
        FamilyPracticeCompletion lone = family("hh-1", "other-activity", 1, OLDER);
        FamilyPracticeCompletion otherHousehold = family("hh-2", "kit-builder", 1, OLDER);

        sweepAndDetach();

        assertThat(completionRepo.findById(v1.getId())).isEmpty();
        assertThat(completionRepo.findById(v2.getId())).isEmpty();
        assertThat(completionRepo.findById(recent.getId())).isPresent();
        assertThat(completionRepo.findById(lone.getId())).isPresent();   // sole row: its activity stays done
        assertThat(completionRepo.findById(otherHousehold.getId())).isPresent();
    }

    @Test
    void drainsMoreThanOneBatch() {
        for (int i = 0; i < 5; i++) run("hh-1", "a@x.com", "s1", ScenarioRun.Status.ABANDONED, OLDER.plusSeconds(i));
        sweep.configure(12, 2, 50);

        assertThat(sweepAndDetach()).isEqualTo(5);
    }

    @Test
    void clearHistoryDeletesOnlyThatHouseholdAndNeverPersonalRuns() {
        run("hh-1", "a@x.com", "s1", ScenarioRun.Status.COMPLETED, RECENT);
        run("hh-1", "a@x.com", "s2", ScenarioRun.Status.IN_PROGRESS, RECENT);
        ScenarioRun personal = run(null, "a@x.com", "s1", ScenarioRun.Status.COMPLETED, RECENT);
        ScenarioRun other = run("hh-2", "a@x.com", "s1", ScenarioRun.Status.COMPLETED, RECENT);
        family("hh-1", "kit-builder", 1, RECENT);
        FamilyPracticeCompletion otherFamily = family("hh-2", "kit-builder", 1, RECENT);

        assertThat(runRepo.deleteAllByHousehold("hh-1")).isEqualTo(2);
        assertThat(completionRepo.deleteAllByHousehold("hh-1")).isEqualTo(1);

        em.clear();
        assertThat(runRepo.findById(personal.getId())).isPresent();
        assertThat(runRepo.findById(other.getId())).isPresent();
        assertThat(completionRepo.findById(otherFamily.getId())).isPresent();
    }
}
