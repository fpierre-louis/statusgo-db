package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.domain.DrillCompletion;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.service.AdminAuditLogService;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The clear's drill-log and week-flag deletion rides on Hibernate flushing the
 * cleared {@code @ElementCollection} maps (the service never calls save). This
 * runs the real service against real repos and asserts the collection-table
 * rows are gone once the persistence context flushes.
 */
@DataJpaTest
@ActiveProfiles("test")
class PracticeHistoryServiceFlushTest {

    @Autowired GroupRepo groups;
    @Autowired ScenarioRunRepo runs;
    @Autowired FamilyPracticeCompletionRepo completions;
    @Autowired PracticeCheckInAnswerRepo checkInAnswers;
    @Autowired PracticeSkillStateRepo skillStates;
    @Autowired PracticeTogetherSessionRepo sessions;
    @Autowired TestEntityManager em;
    @Autowired JdbcTemplate jdbc;

    @Test
    void clearRemovesCollectionTableRowsOnFlush() {
        Group g = new Group();
        g.setGroupId("hh-flush");
        g.setGroupType("Household");
        g.setGroupName("Flush");
        g.getDrillLog().put("go-bag", new DrillCompletion(Instant.parse("2026-10-01T00:00:00Z"), null, "u1"));
        g.getDrillLog().put("water", new DrillCompletion(Instant.parse("2026-10-02T00:00:00Z"), null, "u1"));
        g.getChallengeProgress().put("2026-W40", true);
        groups.saveAndFlush(g);
        em.clear();

        PracticeHistoryService service = new PracticeHistoryService(mock(HouseholdAccessService.class), groups,
                runs, completions, checkInAnswers, skillStates, sessions, mock(AdminAuditLogService.class), mock(WebSocketMessageSender.class));
        PracticeHistoryService.ClearResult result = service.clear("owner@x.com", "hh-flush", true);
        em.flush();

        assertThat(result.removed().drillRecords()).isEqualTo(2);
        assertThat(result.removed().weekFlags()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM group_drill_log WHERE group_id = 'hh-flush'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM group_challenge_progress WHERE group_id = 'hh-flush'", Integer.class)).isZero();
    }

    @Test
    void clearDeletesTheHouseholdsCheckInAnswersAndSkillStateAndNobodyElses() {
        PracticeCheckInAnswer mine = new PracticeCheckInAnswer();
        mine.setHouseholdId("hh-ci-clear");
        mine.setSkillKey("sk");
        mine.setCheckinKey("checkin-sk");
        mine.setContentVersion(1);
        mine.setCorrect(true);
        checkInAnswers.saveAndFlush(mine);
        PracticeCheckInAnswer theirs = new PracticeCheckInAnswer();
        theirs.setHouseholdId("hh-ci-other");
        theirs.setSkillKey("sk");
        theirs.setCheckinKey("checkin-sk");
        theirs.setContentVersion(1);
        theirs.setCorrect(false);
        checkInAnswers.saveAndFlush(theirs);
        for (String hh : new String[] {"hh-ci-clear", "hh-ci-other"}) {
            PracticeSkillState st = new PracticeSkillState();
            st.setHouseholdId(hh);
            st.setSkillKey("sk");
            st.setLastSeenAt(Instant.parse("2026-10-01T00:00:00Z"));
            st.setIntervalDays(9);
            st.setPriorIntervalDays(2);
            st.setNextDueAt(Instant.parse("2026-10-10T00:00:00Z"));
            st.setLastResult("CORRECT");
            st.setUpdatedAt(Instant.parse("2026-10-01T00:00:00Z"));
            skillStates.saveAndFlush(st);
        }
        Group g = new Group();
        g.setGroupId("hh-ci-clear");
        g.setGroupType("Household");
        g.setGroupName("Clear");
        groups.saveAndFlush(g);
        em.clear();

        PracticeHistoryService service = new PracticeHistoryService(mock(HouseholdAccessService.class), groups,
                runs, completions, checkInAnswers, skillStates, sessions, mock(AdminAuditLogService.class), mock(WebSocketMessageSender.class));
        PracticeHistoryService.ClearResult result = service.clear("owner@x.com", "hh-ci-clear", true);
        em.flush();
        em.clear();

        assertThat(result.removed().checkInAnswers()).isEqualTo(1);
        assertThat(result.removed().skillStates()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM practice_checkin_answer WHERE household_id = 'hh-ci-clear'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM practice_skill_state WHERE household_id = 'hh-ci-clear'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM practice_checkin_answer WHERE household_id = 'hh-ci-other'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM practice_skill_state WHERE household_id = 'hh-ci-other'", Integer.class)).isEqualTo(1);
    }
}
