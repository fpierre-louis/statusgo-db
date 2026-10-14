package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.TaskAssignee;
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
import static org.mockito.ArgumentMatchers.anyList;
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
    void assignedOpenTaskCount_countsOpenNonProjectRowsPerGroup_inTwoQueries() {
        when(assigneeRepo.findByEmailIgnoreCase(ME)).thenReturn(List.of(
                assignee(1L), assignee(2L), assignee(3L), assignee(4L), assignee(5L), assignee(1L)));
        when(postRepo.findAllById(anyList())).thenReturn(List.of(
                post(1L, "g1", "task", Post.PostStatus.OPEN),
                post(2L, "g1", "task", Post.PostStatus.IN_PROGRESS),
                post(3L, "g1", "task", Post.PostStatus.DONE),        // terminal
                post(4L, "g1", "project", Post.PostStatus.OPEN),     // a container
                post(5L, "elsewhere", "task", Post.PostStatus.OPEN))); // not one of my groups

        Map<String, Integer> out = service.assignedOpenTaskCounts(ME, Set.of("g1", "g2"));

        assertThat(out).containsExactlyEntriesOf(Map.of("g1", 2));
        verify(assigneeRepo, times(1)).findByEmailIgnoreCase(ME);
        verify(postRepo, times(1)).findAllById(anyList());
    }

    private static TaskAssignee assignee(Long postId) {
        TaskAssignee a = new TaskAssignee();
        a.setPostId(postId);
        a.setEmail(ME);
        return a;
    }

    private static Post post(Long id, String groupId, String kind, Post.PostStatus status) {
        Post p = new Post();
        p.setId(id);
        p.setGroupId(groupId);
        p.setKind(kind);
        p.setStatus(status);
        return p;
    }
}
