package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Post.PostStatus;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.TaskAssigneeRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Audit TK-01 (P0): any group admin could claim any OPEN post by id, which
 * granted read access, live frames and progress rights over another group's
 * private work order. Owner decision D8: same group only, plus an allow-list
 * of community ask/offer kinds. Pure Mockito, real {@link PostReadAuthorizer}.
 */
class PostClaimAuthorizationTest {

    private static final String GROUP = "grp-1";
    private static final String OTHER = "grp-2";
    private static final String OWNER = "owner@x.com";
    private static final String MEMBER = "member@x.com";
    private static final String OTHER_OWNER = "otherowner@x.com";
    private static final String OTHER_CREW = "crew@x.com";
    private static final String AUTHOR = "author@x.com";

    private GroupRepo groupRepo;
    private PostRepo taskRepo;
    private PostService service;

    @BeforeEach
    void setUp() {
        groupRepo = mock(GroupRepo.class);
        taskRepo = mock(PostRepo.class);
        TaskAssigneeRepo assigneeRepo = mock(TaskAssigneeRepo.class);
        when(groupRepo.findByGroupId(GROUP)).thenReturn(Optional.of(
                group(GROUP, OWNER, List.of(MEMBER, AUTHOR))));
        when(groupRepo.findByGroupId(OTHER)).thenReturn(Optional.of(
                group(OTHER, OTHER_OWNER, List.of(OTHER_CREW))));
        service = PostServiceFixture.build(taskRepo, groupRepo, assigneeRepo,
                mock(WorkOrderQuotaService.class), mock(TaskAssignmentService.class));
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static Group group(String id, String owner, List<String> members) {
        Group g = new Group();
        g.setGroupId(id);
        g.setOwnerEmail(owner);
        g.setAdminEmails(List.of());
        g.setMemberEmails(members);
        g.setPendingMemberEmails(List.of("pending@x.com"));
        return g;
    }

    private Post post(Long id, String groupId, String kind) {
        Post p = new Post();
        p.setId(id);
        p.setGroupId(groupId);
        p.setKind(kind);
        p.setTitle("Clear the culvert");
        p.setRequesterEmail(AUTHOR);
        p.setStatus(PostStatus.OPEN);
        when(taskRepo.findById(id)).thenReturn(Optional.of(p));
        return p;
    }

    private void claimSucceeds() {
        when(taskRepo.transitionClaim(anyLong(), any(), any(), anyString(), anyString(), any()))
                .thenReturn(1);
    }

    @Test
    void crossGroupPrivateWorkOrder_is404_andNothingIsClaimed() {
        post(101L, GROUP, "task");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.claimForGroup(101L, OTHER, null, OTHER_OWNER));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(taskRepo, never()).transitionClaim(any(), any(), any(), any(), any(), any());
    }

    @Test
    void crossGroupClaim_isRefusedEvenWhenTheCallerCanReadThePost() {
        // OTHER_OWNER is also a member of GROUP, so they can read the row,
        // but GROUP's work order still can't be claimed for OTHER.
        when(groupRepo.findByGroupId(GROUP)).thenReturn(Optional.of(
                group(GROUP, OWNER, List.of(MEMBER, OTHER_OWNER))));
        post(102L, GROUP, "task");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.claimForGroup(102L, OTHER, null, OTHER_OWNER));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(taskRepo, never()).transitionClaim(any(), any(), any(), any(), any(), any());
    }

    @Test
    void personalTask_is404_forAStranger_andForItsOwnAuthor() {
        post(103L, null, "task");

        ResponseStatusException stranger = assertThrows(ResponseStatusException.class,
                () -> service.claimForGroup(103L, OTHER, null, OTHER_OWNER));
        assertEquals(HttpStatus.NOT_FOUND, stranger.getStatusCode());

        // The author can read their own personal task, but it's not on the
        // allow-list, so no group can claim it.
        when(groupRepo.findByGroupId(OTHER)).thenReturn(Optional.of(
                group(OTHER, AUTHOR, List.of())));
        ResponseStatusException self = assertThrows(ResponseStatusException.class,
                () -> service.claimForGroup(103L, OTHER, null, AUTHOR));
        assertEquals(HttpStatus.NOT_FOUND, self.getStatusCode());
        verify(taskRepo, never()).transitionClaim(any(), any(), any(), any(), any(), any());
    }

    @Test
    void ownGroupWorkOrder_claimsForTheOwner() {
        post(104L, GROUP, "task");
        claimSucceeds();

        assertNotNull(service.claimForGroup(104L, GROUP, null, OWNER));
        verify(taskRepo).transitionClaim(eq(104L), eq(PostStatus.OPEN), eq(PostStatus.CLAIMED),
                eq(GROUP), eq(OWNER), any());
    }

    @Test
    void communityAsk_isOnTheAllowList_andClaimsAcrossGroups() {
        post(105L, null, "ask");
        claimSucceeds();

        assertNotNull(service.claimForGroup(105L, OTHER, OTHER_CREW, OTHER_OWNER));
        verify(taskRepo).transitionClaim(eq(105L), any(), any(), eq(OTHER), eq(OTHER_CREW), any());
    }

    @Test
    void nonManagerCaller_is403() {
        post(106L, GROUP, "task");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.claimForGroup(106L, GROUP, null, MEMBER));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(taskRepo, never()).transitionClaim(any(), any(), any(), any(), any(), any());
    }

    @Test
    void claimerOutsideTheClaimingGroup_is403() {
        post(107L, GROUP, "task");

        ResponseStatusException outsider = assertThrows(ResponseStatusException.class,
                () -> service.claimForGroup(107L, GROUP, "stranger@x.com", OWNER));
        assertEquals(HttpStatus.FORBIDDEN, outsider.getStatusCode());
        ResponseStatusException pending = assertThrows(ResponseStatusException.class,
                () -> service.claimForGroup(107L, GROUP, "pending@x.com", OWNER));
        assertEquals(HttpStatus.FORBIDDEN, pending.getStatusCode());
        verify(taskRepo, never()).transitionClaim(any(), any(), any(), any(), any(), any());
    }

    @Test
    void unknownPost_is404() {
        when(taskRepo.findById(999L)).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.claimForGroup(999L, GROUP, null, OWNER));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }
}
