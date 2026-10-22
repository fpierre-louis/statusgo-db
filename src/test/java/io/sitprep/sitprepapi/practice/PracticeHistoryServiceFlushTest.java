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
                runs, completions, mock(AdminAuditLogService.class), mock(WebSocketMessageSender.class));
        PracticeHistoryService.ClearResult result = service.clear("owner@x.com", "hh-flush", true);
        em.flush();

        assertThat(result.removed().drillRecords()).isEqualTo(2);
        assertThat(result.removed().weekFlags()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM group_drill_log WHERE group_id = 'hh-flush'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM group_challenge_progress WHERE group_id = 'hh-flush'", Integer.class)).isZero();
    }
}
