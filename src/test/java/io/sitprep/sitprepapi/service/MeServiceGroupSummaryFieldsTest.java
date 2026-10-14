package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.MeDto;
import io.sitprep.sitprepapi.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * BE-17 (B4, 2026-10-14; D36): the circle card's "N of M answered" and "N
 * tasks for you", each batched across every group.
 */
class MeServiceGroupSummaryFieldsTest {

    private static final String ME = "me@x.com";

    private final UserInfoRepo userInfoRepo = mock(UserInfoRepo.class);
    private final PostRepo postRepo = mock(PostRepo.class);
    private final TaskAssigneeRepo assigneeRepo = mock(TaskAssigneeRepo.class);
    private MeService service;

    @BeforeEach
    void setUp() {
        service = new MeService(
                userInfoRepo, mock(GroupRepo.class), mock(DemographicRepo.class), mock(MealPlanDataRepo.class),
                mock(EvacuationPlanRepo.class), mock(MeetingPlaceRepo.class), mock(OriginLocationRepo.class),
                mock(EmergencyContactGroupRepo.class), mock(PlanActivationRepo.class), postRepo,
                mock(GroupReadStateRepo.class), mock(GroupPostRepo.class), mock(GroupMutePrefRepo.class),
                mock(HouseholdRitualRepo.class), mock(MeSubfetchService.class), mock(UserInfoService.class),
                mock(PlatformAccessService.class), mock(GoBagService.class), mock(EmergencySupportService.class),
                mock(StandingConditionService.class), mock(HouseholdReadinessService.class), new ObjectMapper(),
                mock(AgencyStaffService.class));
        service.setTaskAssigneeRepo(assigneeRepo);
    }

    private static Group group(String id, String alert, Instant start, String... members) {
        Group g = new Group();
        g.setGroupId(id);
        g.setGroupType("Community");
        g.setAlert(alert);
        g.setAlertActivatedAt(start);
        g.setOwnerEmail(members[0]);
        g.setMemberEmails(new ArrayList<>(List.of(members)));
        return g;
    }

    private static UserInfo user(String email, String status, Instant at) {
        UserInfo u = new UserInfo();
        u.setUserEmail(email);
        u.setUserStatus(status);
        u.setUserStatusLastUpdated(at);
        return u;
    }

    @Test
    void checkInRollup_onlyWhileActive_onlyForTheirOwnGroups_inOneQuery() {
        Instant start = Instant.now().minus(Duration.ofHours(1));
        Group running = group("running", "Active", start, "o@x.com", ME, "a@x.com", "b@x.com");
        Group calm = group("calm", "Not Active", null, ME, "c@x.com");
        Group notMine = group("staffOnly", "Active", start, "o@x.com", "d@x.com");
        when(userInfoRepo.findByUserEmailIn(anyList())).thenReturn(List.of(
                user(ME, "SAFE", start.plusSeconds(60)),
                user("a@x.com", "HELP", start.plusSeconds(60)),
                user("b@x.com", "INJURED", start.plusSeconds(60)),
                // answered before the check-in started: noResponse here
                user("o@x.com", "SAFE", start.minusSeconds(60))));

        Map<String, MeDto.CheckInRollup> out =
                service.checkInRollupsFor(List.of(running, calm, notMine), ME, Set.of());

        assertThat(out).containsOnlyKeys("running");
        MeDto.CheckInRollup r = out.get("running");
        assertThat(r.total()).isEqualTo(4);
        assertThat(r.accounted()).isEqualTo(3);
        assertThat(r.help()).isEqualTo(2); // help + injured, the lock screen's urgent count
        verify(userInfoRepo, times(1)).findByUserEmailIn(anyList());
    }

    @Test
    void checkInRollup_skipsHouseholds_andCostsNothingWhenCalm() {
        Group hh = group("hh", "Active", Instant.now(), ME);
        assertThat(service.checkInRollupsFor(List.of(hh), ME, Set.of("hh"))).isEmpty();
        assertThat(service.checkInRollupsFor(List.of(group("calm", "Not Active", null, ME)), ME, Set.of())).isEmpty();
        verify(userInfoRepo, never()).findByUserEmailIn(anyList());
    }

    @Test
    void assignedOpenTaskCount_isOneAggregateQuery_perGroupCountsPassThrough() {
        // The filtering (my groups, open, not a project) is the query's job;
        // TaskAssigneeOpenCountRepoTest runs it on H2.
        when(assigneeRepo.countOpenAssignedByGroup(eq(ME), anyCollection(), anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{"g1", 2L}, new Object[]{"g2", 0L}));

        Map<String, Integer> out = service.assignedOpenTaskCounts(ME, Set.of("g1", "g2"));

        assertThat(out).containsExactlyEntriesOf(Map.of("g1", 2));
        verify(assigneeRepo, times(1)).countOpenAssignedByGroup(eq(ME), eq(Set.of("g1", "g2")), anyCollection());
        verify(assigneeRepo, never()).findByEmailIgnoreCase(anyString());
        verify(postRepo, never()).findAllById(anyList());
    }

    @Test
    void assignedOpenTaskCount_costsNothingWithoutGroups() {
        assertThat(service.assignedOpenTaskCounts(ME, Set.of())).isEmpty();
        verifyNoInteractions(assigneeRepo);
    }
}
