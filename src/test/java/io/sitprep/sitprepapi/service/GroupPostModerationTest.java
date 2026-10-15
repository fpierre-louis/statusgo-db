package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.GroupPost;
import io.sitprep.sitprepapi.domain.GroupPostComment;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.GroupPostCommentDto;
import io.sitprep.sitprepapi.dto.GroupPostDto;
import io.sitprep.sitprepapi.repo.GroupPostCommentRepo;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.GroupReadStateRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Audit FD-06 / FD-08 (lane B6): owners and admins remove other members'
 * posts and comments, leaving a tombstone and an audit row; everyone else is
 * refused with 403; the author still hard-deletes; a removed post can't be
 * edited back. Also: {@code authorUserId} on the post DTO. Pure Mockito.
 */
class GroupPostModerationTest {

    private static final String GROUP_ID = "grp-1";
    private static final String OWNER = "owner@x.com";
    private static final String ADMIN = "admin@x.com";
    private static final String MEMBER = "member@x.com";
    private static final String OTHER = "other@x.com";

    private GroupPostRepo postRepo;
    private GroupRepo groupRepo;
    private UserInfoRepo userInfoRepo;
    private WebSocketMessageSender ws;
    private GroupContentRemovals removals;
    private GroupPostService service;
    private NotificationService notifications;

    @BeforeEach
    void setUp() {
        postRepo = mock(GroupPostRepo.class);
        groupRepo = mock(GroupRepo.class);
        userInfoRepo = mock(UserInfoRepo.class);
        ws = mock(WebSocketMessageSender.class);
        removals = mock(GroupContentRemovals.class);
        notifications = mock(NotificationService.class);
        service = new GroupPostService(postRepo, userInfoRepo, groupRepo,
                notifications, ws,
                mock(GroupPostReactionService.class),
                mock(GroupReadStateRepo.class),
                mock(GroupPostThreadPresenceService.class),
                mock(PublisherPublishAuditService.class),
                mock(MentionService.class),
                removals);
        Group g = new Group();
        g.setGroupId(GROUP_ID);
        g.setOwnerEmail(OWNER);
        g.setAdminEmails(List.of(ADMIN));
        g.setMemberEmails(new ArrayList<>(List.of(MEMBER, OTHER)));
        when(groupRepo.findByGroupId(GROUP_ID)).thenReturn(Optional.of(g));
        when(postRepo.save(any(GroupPost.class))).thenAnswer(inv -> inv.getArgument(0));
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private GroupPost memberPost() {
        GroupPost p = new GroupPost();
        p.setId(7L);
        p.setGroupId(GROUP_ID);
        p.setAuthor(MEMBER);
        p.setContent("rude words");
        p.setImageKey("post/a.jpg");
        p.setLatitude(40.0);
        p.setLongitude(-111.0);
        p.setTags(new ArrayList<>(List.of("x")));
        p.setPinnedAt(Instant.now());
        p.setPinnedBy(ADMIN);
        when(postRepo.findById(7L)).thenReturn(Optional.of(p));
        return p;
    }

    private void fireAfterCommit() {
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
        }
    }

    @Test
    void adminRemovesAnotherMembersPost_leavesTombstoneAndAuditRow() {
        GroupPost p = memberPost();

        service.deletePostAndBroadcast(7L, ADMIN);

        verify(postRepo, never()).delete(any());
        verify(removals).recordPost(eq(ADMIN), eq(7L), contains("rude words"));
        verify(notifications).scrubRemovedPostNotifications(7L); // FD-06: inbox rows stop quoting it
        assertEquals("", p.getContent());
        assertNull(p.getImageKey());
        assertNull(p.getLatitude());
        assertTrue(p.getTags().isEmpty());
        assertNull(p.getPinnedAt(), "a removed post is no longer pinned");

        fireAfterCommit();
        ArgumentCaptor<GroupPostDto> frame = ArgumentCaptor.forClass(GroupPostDto.class);
        verify(ws).sendNewGroupPost(eq(GROUP_ID), frame.capture());
        assertTrue(frame.getValue().isRemovedByAdmin());
        assertNotNull(frame.getValue().getRemovedAt());
        assertEquals("", frame.getValue().getContent());
    }

    @Test
    void ownerMayRemoveToo() {
        memberPost();
        service.deletePostAndBroadcast(7L, OWNER);
        verify(removals).recordPost(eq(OWNER), eq(7L), anyString());
    }

    @Test
    void plainMemberRemovingSomeoneElsesPost_is403_andNothingChanges() {
        GroupPost p = memberPost();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.deletePostAndBroadcast(7L, OTHER));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertEquals("rude words", p.getContent());
        verify(postRepo, never()).save(any());
        verify(removals, never()).recordPost(anyString(), any(), anyString());
    }

    @Test
    void unknownPost_is404_not500() {
        when(postRepo.findById(99L)).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.deletePostAndBroadcast(99L, ADMIN));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void authorStillHardDeletes() {
        GroupPost p = memberPost();
        service.deletePostAndBroadcast(7L, MEMBER);
        verify(postRepo).delete(p);
        verify(ws).sendGroupPostDeletion(GROUP_ID, 7L);
        verify(removals, never()).recordPost(anyString(), any(), anyString());
    }

    @Test
    void authorCannotEditARemovedPostBack() {
        memberPost();
        when(removals.isPostRemoved(7L)).thenReturn(true);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.editPost(7L, MEMBER, "back again", null, null, null, null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void anAdminCannotPinARemovedPost() {
        GroupPost p = memberPost();
        p.setPinnedAt(null);
        p.setPinnedBy(null);
        when(removals.isPostRemoved(7L)).thenReturn(true);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.pinPost(7L, ADMIN));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertNull(p.getPinnedAt());
        verify(postRepo, never()).save(any());
    }

    @Test
    void readsCarryTheTombstoneAndTheAuthorUserId() {
        memberPost();
        UserInfo u = new UserInfo();
        u.setId("uid-member");
        u.setUserEmail(MEMBER);
        u.setUserFirstName("Mem");
        when(userInfoRepo.findByUserEmail(MEMBER)).thenReturn(Optional.of(u));
        Instant at = Instant.parse("2026-10-13T12:00:00Z");
        when(removals.removedPosts(anyCollection())).thenReturn(Map.of(7L, at));

        GroupPostDto dto = service.getPostDtoById(7L, ADMIN).orElseThrow();

        assertEquals("uid-member", dto.getAuthorUserId(), "FD-08: author id for tap-to-profile");
        assertTrue(dto.isRemovedByAdmin());
        assertEquals(at, dto.getRemovedAt());
    }

    // ---------------------------------------------------------------- comments

    @Test
    void adminRemovesAComment_plainMemberCannot_authorDeletes() {
        GroupPostCommentRepo commentRepo = mock(GroupPostCommentRepo.class);
        GroupPostService postService = mock(GroupPostService.class);
        when(postService.isGroupAdminOrOwner(GROUP_ID, ADMIN)).thenReturn(true);
        GroupPostCommentService comments = new GroupPostCommentService(commentRepo, postRepo,
                userInfoRepo, ws, mock(NotificationService.class),
                mock(GroupPostCommentReactionService.class), postService,
                mock(MentionService.class), mock(BlockService.class), removals);
        memberPost();
        GroupPostComment c = new GroupPostComment();
        c.setId(11L);
        c.setPostId(7L);
        c.setAuthor(MEMBER);
        c.setContent("mean reply");
        when(commentRepo.findById(11L)).thenReturn(Optional.of(c));
        when(commentRepo.save(any(GroupPostComment.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> comments.deleteOrRemoveAsCaller(11L, OTHER));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertEquals("mean reply", c.getContent());

        comments.deleteOrRemoveAsCaller(11L, ADMIN);
        assertEquals("", c.getContent());
        verify(commentRepo, never()).deleteById(any());
        verify(removals).recordComment(eq(ADMIN), eq(11L), contains("mean reply"));
        fireAfterCommit();
        ArgumentCaptor<GroupPostCommentDto> frame = ArgumentCaptor.forClass(GroupPostCommentDto.class);
        verify(ws).sendNewGroupPostComment(eq(7L), frame.capture());
        assertTrue(frame.getValue().isRemovedByAdmin());

        comments.deleteOrRemoveAsCaller(11L, MEMBER);
        verify(commentRepo).deleteById(11L);
    }

    @Test
    void myGroupsLatestPreviewSaysARemovedPostWasRemoved() {
        // F6 / FD-06: the latest-post summary carries removedByAdmin and no text.
        GroupPost p = memberPost();
        p.setContent("");
        p.setTimestamp(Instant.now());
        when(postRepo.findLatestPostsByGroupIds(List.of(GROUP_ID))).thenReturn(List.of(p));
        when(userInfoRepo.findByUserEmailIn(anyList())).thenReturn(List.of());
        when(removals.removedPosts(anyCollection())).thenReturn(Map.of(7L, Instant.now()));

        var out = service.getLatestPostsForGroups(List.of(GROUP_ID), MEMBER);

        assertTrue(out.get(GROUP_ID).isRemovedByAdmin());
        assertNull(out.get(GROUP_ID).getContent());
    }

    @Test
    void myGroupsLatestPreviewKeepsAnOrdinaryPost() {
        GroupPost p = memberPost();
        p.setTimestamp(Instant.now());
        when(postRepo.findLatestPostsByGroupIds(List.of(GROUP_ID))).thenReturn(List.of(p));
        when(userInfoRepo.findByUserEmailIn(anyList())).thenReturn(List.of());
        when(removals.removedPosts(anyCollection())).thenReturn(Map.of());

        var out = service.getLatestPostsForGroups(List.of(GROUP_ID), MEMBER);

        assertFalse(out.get(GROUP_ID).isRemovedByAdmin());
        assertEquals("rude words", out.get(GROUP_ID).getContent());
    }
}
