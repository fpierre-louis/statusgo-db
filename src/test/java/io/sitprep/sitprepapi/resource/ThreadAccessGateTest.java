package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.GroupPost;
import io.sitprep.sitprepapi.domain.GroupPostComment;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.dto.GroupPostCommentDto;
import io.sitprep.sitprepapi.repo.GroupPostCommentRepo;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostCommentRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.TaskAssigneeRepo;
import io.sitprep.sitprepapi.service.GroupPostCommentReactionService;
import io.sitprep.sitprepapi.service.GroupPostCommentService;
import io.sitprep.sitprepapi.service.GroupPostReactionService;
import io.sitprep.sitprepapi.service.PostReactionService;
import io.sitprep.sitprepapi.service.PostReadAuthorizer;
import io.sitprep.sitprepapi.service.ThreadAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * A post id is not permission to touch the thread it belongs to.
 *
 * <p>The reaction and comment endpoints took a numeric id off the path and did
 * the work. Each verified the caller was signed in; none verified they could see
 * the thread. Ids are sequential, so:</p>
 *
 * <ul>
 *   <li>reading a private family or agency chat's reaction roster was a
 *       for-loop, and the roster is {@code (userEmail, addedAt)} — a list of
 *       member email addresses;</li>
 *   <li>commenting into that chat was the same loop with a body, and the comment
 *       reaches every member as a push notification. Overriding the author from
 *       the token stopped you posting <em>as</em> someone else; nothing stopped
 *       you posting <em>into</em> their group.</li>
 * </ul>
 *
 * <p>The last test is the one that keeps the fix honest: the community feed's
 * heart button must still work for a stranger, because a groupless community
 * post is a deliberate broadcast. A gate that also closed that would be a
 * regression wearing a security fix's clothes.</p>
 */
class ThreadAccessGateTest {

    private static final Long GROUP_POST_ID = 100L;
    private static final Long GROUP_COMMENT_ID = 200L;
    private static final Long COMMUNITY_POST_ID = 300L;
    private static final String GROUP_ID = "grp-private";
    private static final String MEMBER = "member@x.com";
    private static final String OUTSIDER = "outsider@x.com";

    private GroupPostReactionService groupPostReactions;
    private GroupPostCommentReactionService groupCommentReactions;
    private PostReactionService postReactions;
    private GroupPostCommentService groupComments;

    private GroupPostReactionResource groupPostReactionResource;
    private GroupPostCommentReactionResource groupCommentReactionResource;
    private PostReactionResource postReactionResource;
    private GroupPostCommentResource groupCommentResource;

    @BeforeEach
    void setUp() {
        GroupRepo groupRepo = mock(GroupRepo.class);
        GroupPostRepo groupPostRepo = mock(GroupPostRepo.class);
        GroupPostCommentRepo groupPostCommentRepo = mock(GroupPostCommentRepo.class);
        PostRepo postRepo = mock(PostRepo.class);
        PostCommentRepo postCommentRepo = mock(PostCommentRepo.class);

        Group group = new Group();
        group.setGroupId(GROUP_ID);
        group.setOwnerEmail("owner@x.com");
        group.setMemberEmails(List.of(MEMBER));
        when(groupRepo.findByGroupId(GROUP_ID)).thenReturn(Optional.of(group));

        GroupPost gp = new GroupPost();
        gp.setId(GROUP_POST_ID);
        gp.setGroupId(GROUP_ID);
        when(groupPostRepo.findById(GROUP_POST_ID)).thenReturn(Optional.of(gp));

        GroupPostComment gc = new GroupPostComment();
        gc.setId(GROUP_COMMENT_ID);
        gc.setPostId(GROUP_POST_ID);
        when(groupPostCommentRepo.findById(GROUP_COMMENT_ID)).thenReturn(Optional.of(gc));

        // Groupless, non-personal: a community feed post. Public by design.
        Post community = new Post();
        community.setId(COMMUNITY_POST_ID);
        community.setKind("post");
        when(postRepo.findById(COMMUNITY_POST_ID)).thenReturn(Optional.of(community));

        ThreadAccessService threadAccess = new ThreadAccessService(
                groupPostRepo, groupPostCommentRepo, postRepo, postCommentRepo,
                new PostReadAuthorizer(groupRepo, mock(TaskAssigneeRepo.class)));

        groupPostReactions = mock(GroupPostReactionService.class);
        groupCommentReactions = mock(GroupPostCommentReactionService.class);
        postReactions = mock(PostReactionService.class);
        groupComments = mock(GroupPostCommentService.class);

        groupPostReactionResource = new GroupPostReactionResource(groupPostReactions, threadAccess);
        groupCommentReactionResource = new GroupPostCommentReactionResource(groupCommentReactions, threadAccess);
        postReactionResource = new PostReactionResource(postReactions, threadAccess);
        groupCommentResource = new GroupPostCommentResource(groupComments, threadAccess);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        email, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private void assertNotFound(Runnable call) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, call::run);
        // 404, not 403 — otherwise the gate becomes the enumeration oracle it
        // was added to close.
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void outsiderCannotReadAPrivateChatsReactionRoster() {
        authenticateAs(OUTSIDER);
        assertNotFound(() -> groupPostReactionResource.list(GROUP_POST_ID));
        verify(groupPostReactions, never()).loadByPostId(anyLong());
    }

    @Test
    void outsiderCannotReactInAPrivateChat() {
        authenticateAs(OUTSIDER);
        assertNotFound(() -> groupPostReactionResource.add(GROUP_POST_ID,
                new GroupPostReactionResource.AddReactionRequest("❤")));
        verify(groupPostReactions, never()).add(anyLong(), anyString(), anyString());
    }

    @Test
    void outsiderCannotReadACommentsReactionRoster() {
        authenticateAs(OUTSIDER);
        assertNotFound(() -> groupCommentReactionResource.list(GROUP_COMMENT_ID));
        verify(groupCommentReactions, never()).loadByGroupPostCommentId(anyLong());
    }

    @Test
    void outsiderCannotPushAMessageIntoAPrivateChat() {
        // The one that reaches every member's lock screen.
        authenticateAs(OUTSIDER);
        GroupPostCommentDto dto = new GroupPostCommentDto();
        dto.setPostId(GROUP_POST_ID);
        dto.setContent("hello");
        assertNotFound(() -> groupCommentResource.createComment(dto));
        verify(groupComments, never()).createCommentFromDto(any());
    }

    @Test
    void memberCanStillDoAllOfIt() {
        authenticateAs(MEMBER);
        when(groupPostReactions.loadByPostId(GROUP_POST_ID)).thenReturn(Map.of());
        assertEquals(HttpStatus.OK, groupPostReactionResource.list(GROUP_POST_ID).getStatusCode());

        when(groupCommentReactions.loadByGroupPostCommentId(GROUP_COMMENT_ID)).thenReturn(Map.of());
        assertEquals(HttpStatus.OK, groupCommentReactionResource.list(GROUP_COMMENT_ID).getStatusCode());

        GroupPostCommentDto dto = new GroupPostCommentDto();
        dto.setPostId(GROUP_POST_ID);
        when(groupComments.createCommentFromDto(any())).thenReturn(dto);
        assertEquals(HttpStatus.OK, groupCommentResource.createComment(dto).getStatusCode());
    }

    @Test
    void aStrangerCanStillHeartACommunityPost() {
        // Groupless and not a personal-scope kind: a deliberate broadcast to the
        // neighborhood. PostReadAuthorizer says yes, and this gate must not
        // second-guess it — closing this would be a regression, not a fix.
        authenticateAs(OUTSIDER);
        when(postReactions.loadByPostId(COMMUNITY_POST_ID)).thenReturn(Map.of());
        assertEquals(HttpStatus.OK, postReactionResource.list(COMMUNITY_POST_ID).getStatusCode());
        verify(postReactions).loadByPostId(COMMUNITY_POST_ID);
    }

    @Test
    void anUnknownIdIsAlsoJustNotFound() {
        authenticateAs(MEMBER);
        assertNotFound(() -> groupPostReactionResource.list(999_999L));
    }
}
