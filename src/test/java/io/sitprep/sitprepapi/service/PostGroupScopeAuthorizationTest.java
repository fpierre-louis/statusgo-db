package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.repo.AskBookmarkRepo;
import io.sitprep.sitprepapi.repo.FollowRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostConfirmRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.TaskAssigneeRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Regression cover for the group-scoped read exposure
 * ({@code docs/audits/post-by-id-authorization.md}).
 *
 * <p>{@code GET /api/posts/{id}} authenticated the caller and then never
 * checked entitlement, on an id space that is a dense integer sequence. The
 * fix is {@link PostReadAuthorizer}'s group branch.</p>
 *
 * <p><b>This class tests the rejections as deliberately as the arms.</b> Four
 * bypasses were considered and declined — PENDING members, moderation,
 * platform admin, agency staff — each for a recorded reason. A decision that
 * lives only in a Javadoc drifts back into an open question the next time
 * someone reads the code; a failing test is what keeps it decided.</p>
 */
class PostGroupScopeAuthorizationTest {

    private static final String GROUP = "grp-1";
    private static final String CLAIMER_GROUP = "grp-2";
    private static final String OWNER = "owner@x.com";
    private static final String ADMIN = "admin@x.com";
    private static final String MEMBER = "member@x.com";
    private static final String PENDING = "pending@x.com";
    private static final String STRANGER = "stranger@x.com";
    private static final String AUTHOR = "author@x.com";

    private GroupRepo groupRepo;
    private TaskAssigneeRepo assigneeRepo;
    private PostRepo taskRepo;
    private PostReadAuthorizer authorizer;
    private PostService service;

    @BeforeEach
    void setUp() {
        groupRepo = mock(GroupRepo.class);
        assigneeRepo = mock(TaskAssigneeRepo.class);
        taskRepo = mock(PostRepo.class);
        authorizer = new PostReadAuthorizer(groupRepo, assigneeRepo);

        when(groupRepo.findByGroupId(GROUP)).thenReturn(Optional.of(group(GROUP)));
        when(groupRepo.findByGroupId(CLAIMER_GROUP)).thenReturn(Optional.of(claimerGroup()));

        service = new PostService(
                taskRepo,
                mock(UserInfoRepo.class),
                mock(NominatimGeocodeService.class),
                mock(WebSocketMessageSender.class),
                mock(AlertModeService.class),
                mock(FollowRepo.class),
                mock(BlockService.class),
                mock(PostReactionService.class),
                mock(PostCommentService.class),
                mock(StorageService.class),
                groupRepo,
                mock(PublisherPublishAuditService.class),
                mock(AgencyAuthorizationService.class),
                mock(PostConfirmRepo.class),
                mock(AskBookmarkRepo.class),
                mock(WorkOrderQuotaService.class),
                mock(AdminAuditLogService.class),
                assigneeRepo,
                mock(TaskAssignmentService.class),
                mock(AgencyJurisdictionService.class),
                mock(CivicAgencyService.class),
                authorizer);
    }

    private Group group(String id) {
        Group g = new Group();
        g.setGroupId(id);
        g.setOwnerEmail(OWNER);
        g.setAdminEmails(List.of(ADMIN));
        g.setMemberEmails(List.of(MEMBER));
        g.setPendingMemberEmails(List.of(PENDING));
        return g;
    }

    private Group claimerGroup() {
        Group g = new Group();
        g.setGroupId(CLAIMER_GROUP);
        g.setOwnerEmail("claimowner@x.com");
        g.setAdminEmails(List.of());
        g.setMemberEmails(List.of("crew@x.com"));
        g.setPendingMemberEmails(List.of());
        return g;
    }

    /** A group-scoped work order authored by someone outside the group. */
    private Post groupPost() {
        Post p = new Post();
        p.setId(101L);
        p.setGroupId(GROUP);
        p.setKind("task");
        p.setRequesterEmail(AUTHOR);
        return p;
    }

    // ------------------------------------------------------------------
    // Arms — each grants read
    // ------------------------------------------------------------------

    @Test
    void arm1_membership_grantsRead() {
        assertTrue(authorizer.canRead(groupPost(), OWNER));
        assertTrue(authorizer.canRead(groupPost(), ADMIN));
        assertTrue(authorizer.canRead(groupPost(), MEMBER));
    }

    @Test
    void arm2_requesterReadsOwnRow_evenWithoutMembership() {
        // AUTHOR is on no roster of GROUP. Read must not be narrower than
        // write, where ensureCanEditTask grants the author unconditionally.
        assertTrue(authorizer.canRead(groupPost(), AUTHOR));
    }

    @Test
    void arm3_claimerGroupMembersReadTheClaimedRow() {
        Post p = groupPost();
        p.setClaimedByGroupId(CLAIMER_GROUP);

        // "crew@x.com" belongs to the claiming group only — not to GROUP.
        assertTrue(authorizer.canRead(p, "crew@x.com"));
        // Without the claim they would have no path to it.
        assertFalse(authorizer.canRead(groupPost(), "crew@x.com"));
    }

    @Test
    void arm4_assigneeReadsTheirOwnWork() {
        when(assigneeRepo.existsByPostIdAndEmailIgnoreCase(101L, "worker@x.com")).thenReturn(true);
        assertTrue(authorizer.canRead(groupPost(), "worker@x.com"));
    }

    // ------------------------------------------------------------------
    // Rejections — each was considered and declined
    // ------------------------------------------------------------------

    @Test
    void rejection_pendingMemberIsDenied() {
        // A request to join is not a grant of history. Matches the write gate,
        // which 403s pending members (GroupPostSecurityTest). The sanctioned
        // pre-join surface is GroupPreviewDto, which is sanitized by design.
        assertFalse(authorizer.canRead(groupPost(), PENDING));
    }

    @Test
    void rejection_platformAdminGetsNoBypass() {
        // No platform-admin arm exists, by decision: no surface needs one and
        // an unused bypass is attack surface. PlatformAccessService is not a
        // dependency of PostReadAuthorizer at all, so "the platform admin"
        // is simply a non-member here and is denied like any other.
        assertFalse(authorizer.canRead(groupPost(), "platform-admin@sitprep.app"));
    }

    @Test
    void rejection_moderatorGetsNoBypass() {
        // Moderation never fetches a post by id — CommunityReportService
        // captures a server-side contentPreview at report time and the console
        // reads that row, so no bypass is needed here.
        assertFalse(authorizer.canRead(groupPost(), "moderator@sitprep.app"));
    }

    @Test
    void rejection_strangerAndAnonymousAreDenied() {
        assertFalse(authorizer.canRead(groupPost(), STRANGER));
        assertFalse(authorizer.canRead(groupPost(), null));
        assertFalse(authorizer.canRead(groupPost(), "  "));
    }

    @Test
    void unknownGroupIsDenied_withoutRevealingThatItIsUnknown() {
        Post p = groupPost();
        p.setGroupId("no-such-group");
        when(groupRepo.findByGroupId("no-such-group")).thenReturn(Optional.empty());
        assertFalse(authorizer.canRead(p, MEMBER));
    }

    // ------------------------------------------------------------------
    // Call sites
    // ------------------------------------------------------------------

    @Test
    void findDtoById_isEmptyForNonMember_soTheResourceRenders404() {
        when(taskRepo.findById(101L)).thenReturn(Optional.of(groupPost()));
        // Empty, not an exception: the resource maps Optional -> 404, so
        // "not yours" and "not there" are indistinguishable. A 403 against a
        // dense integer id space would be a corpus census.
        assertTrue(service.findDtoById(101L, STRANGER).isEmpty());
    }

    @Test
    void listByGroup_deniesNonMemberEvenWhenTheyOwnARowInside() {
        // The sharpest boundary in the fix: per-row arms grant access to a
        // ROW, never to a group's whole board. AUTHOR owns a row in GROUP and
        // canRead grants them that row — but not the board it sits on.
        assertTrue(authorizer.canRead(groupPost(), AUTHOR));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.listByGroup(GROUP, null, AUTHOR));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(taskRepo, never()).findByGroupIdOrderByCreatedAtDesc(anyString());
    }

    @Test
    void listByGroup_allowsMember() {
        when(taskRepo.findByGroupIdOrderByCreatedAtDesc(GROUP)).thenReturn(List.of());
        assertTrue(service.listByGroup(GROUP, null, MEMBER).isEmpty());
        verify(taskRepo).findByGroupIdOrderByCreatedAtDesc(eq(GROUP));
    }

    // ------------------------------------------------------------------
    // Write side (commit 4)
    // ------------------------------------------------------------------

    @Test
    void create_rejectsPostingIntoAGroupYouDoNotBelongTo() {
        Post incoming = new Post();
        incoming.setGroupId(GROUP);
        incoming.setKind("post");
        incoming.setDescription("planted");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.create(incoming, STRANGER));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(taskRepo, never()).save(any(Post.class));
    }

    @Test
    void create_rejectsUnknownGroup() {
        when(groupRepo.findByGroupId("no-such-group")).thenReturn(Optional.empty());
        Post incoming = new Post();
        incoming.setGroupId("no-such-group");
        incoming.setKind("post");
        incoming.setDescription("planted");

        assertThrows(IllegalArgumentException.class,
                () -> service.create(incoming, MEMBER));
        verify(taskRepo, never()).save(any(Post.class));
    }
}
