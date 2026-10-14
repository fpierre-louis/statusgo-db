package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** POST /api/groups/{id}/leave — docs/epics/groups/groups-admin-fixes/EXEC.md (FE repo). */
class GroupLeaveServiceTest {

    private GroupRepo groups;
    private UserInfoRepo users;
    private GroupService groupService;
    private HouseholdProvisioningService provisioning;
    private GroupLeaveService service;
    private Group g;
    private UserInfo member;

    @BeforeEach
    void setUp() {
        groups = mock(GroupRepo.class);
        users = mock(UserInfoRepo.class);
        groupService = mock(GroupService.class);
        provisioning = mock(HouseholdProvisioningService.class);
        service = new GroupLeaveService(groups, users, groupService, provisioning);

        g = new Group();
        g.setGroupId("hh-1");
        g.setOwnerEmail("owner@x.com");
        g.setAdminEmails(new ArrayList<>(List.of("admin@x.com")));
        g.setMemberEmails(new ArrayList<>(List.of("member@x.com", "admin@x.com")));
        when(groups.findByGroupId("hh-1")).thenReturn(Optional.of(g));

        member = new UserInfo();
        member.setUserEmail("member@x.com");
        member.setBaseHouseholdId("somewhere-else");
        when(users.findByUserEmailIgnoreCase("member@x.com")).thenReturn(Optional.of(member));
        when(users.save(any(UserInfo.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }

    @Test
    void aMemberLeaves() {
        service.leave("hh-1", " Member@X.com ");
        verify(groupService).removeMember("hh-1", "member@x.com");
        verify(provisioning, never()).ensureBaseHousehold(any());
    }

    @Test
    void anAdminLeaves() {
        service.leave("hh-1", "admin@x.com");
        verify(groupService).removeMember("hh-1", "admin@x.com");
    }

    @Test
    void theOwnerCannotLeave() {
        assertStatus(() -> service.leave("hh-1", "OWNER@x.com"), HttpStatus.CONFLICT);
        verify(groupService, never()).removeMember(anyString(), anyString());
    }

    @Test
    void aStrangerAndAnUnknownGroupGetTheSame404() {
        assertStatus(() -> service.leave("hh-1", "stranger@x.com"), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.leave("nope", "member@x.com"), HttpStatus.NOT_FOUND);
        verify(groupService, never()).removeMember(anyString(), anyString());
    }

    @Test
    void aPendingRequesterWithdrawsTheirRequest() {
        // JN-21 / D3: /leave from a requester is "withdraw my request", a 204.
        when(groupService.withdrawPendingRequest("hh-1", "asker@x.com")).thenReturn(true);
        service.leave("hh-1", "Asker@x.com");
        verify(groupService).withdrawPendingRequest("hh-1", "asker@x.com");
        verify(groupService, never()).removeMember(anyString(), anyString());
        verify(provisioning, never()).ensureBaseHousehold(any());
    }

    @Test
    void withdrawRemovesOnlyTheCallerAndTheFrameNamesNobody() {
        // The real GroupService behind /leave's pending branch.
        GroupRepo repo = mock(GroupRepo.class);
        io.sitprep.sitprepapi.websocket.WebSocketMessageSender ws =
                mock(io.sitprep.sitprepapi.websocket.WebSocketMessageSender.class);
        GroupService real = new GroupService(repo, users, ws, mock(HouseholdEventService.class),
                mock(NotificationService.class), mock(CheckInRequestService.class));
        Group church = new Group();
        church.setGroupId("g-1");
        church.setGroupType("Church");
        church.setOwnerEmail("owner@x.com");
        church.setMemberEmails(new ArrayList<>(List.of("owner@x.com")));
        church.setPendingMemberEmails(new ArrayList<>(List.of("asker@x.com", "other@x.com")));
        when(repo.findByGroupId("g-1")).thenReturn(Optional.of(church));
        when(repo.save(any(Group.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(real.withdrawPendingRequest("g-1", " ASKER@x.com")).isTrue();
        assertThat(church.getPendingMemberEmails()).containsExactly("other@x.com");
        assertThat(church.getMemberEmails()).containsExactly("owner@x.com");

        var frame = org.mockito.ArgumentCaptor.forClass(io.sitprep.sitprepapi.dto.GroupMembershipFrame.class);
        verify(ws).sendGroupMembership(org.mockito.ArgumentMatchers.eq("g-1"), frame.capture());
        // Plain members hear the members topic too: no requester email (D3, D6).
        assertThat(frame.getValue().action()).isEqualTo(GroupService.PENDING_CHANGED);
        assertThat(frame.getValue().userEmail()).isNull();
        assertThat(frame.getValue().role()).isNull();

        // Nothing to withdraw: false, nothing written.
        assertThat(real.withdrawPendingRequest("g-1", "stranger@x.com")).isFalse();
        verify(repo, org.mockito.Mockito.times(1)).save(any(Group.class));
    }

    @Test
    void leavingYourBaseHouseholdReanchorsYou() {
        member.setBaseHouseholdId("hh-1");
        service.leave("hh-1", "member@x.com");
        assertThat(member.getBaseHouseholdId()).isNull(); // cleared before re-provisioning
        verify(provisioning).ensureBaseHousehold(member);
    }
}
