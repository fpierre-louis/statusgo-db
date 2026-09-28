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

/** POST /api/groups/{id}/leave — docs/epics/groups-admin-fixes/EXEC.md (FE repo). */
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
    void leavingYourBaseHouseholdReanchorsYou() {
        member.setBaseHouseholdId("hh-1");
        service.leave("hh-1", "member@x.com");
        assertThat(member.getBaseHouseholdId()).isNull(); // cleared before re-provisioning
        verify(provisioning).ensureBaseHousehold(member);
    }
}
