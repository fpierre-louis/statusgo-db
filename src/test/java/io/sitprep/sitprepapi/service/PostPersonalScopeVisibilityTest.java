package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Post.PostStatus;
import io.sitprep.sitprepapi.dto.PostDto;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Regression cover for the personal-task read exposure
 * ({@code docs/audits/personal-task-feed-exposure.md}).
 *
 * <p>Personal preparedness tasks are {@code kind=task}/{@code project} with
 * {@code groupId=null}. Groupless normally means "public to the
 * neighborhood", so before {@code PostReadAuthorizer} these rows reached
 * strangers through FOUR different paths. One test per path, each asserting
 * both halves — withheld from a stranger, still returned to its owner —
 * because a filter that hides the row from its author is not a fix.</p>
 *
 * <p>The unaffected-kinds test is the guard against over-filtering: asks,
 * offers, marketplace, civic reports, official agency posts and news must
 * still flow, or this "fix" silently empties the community feed.</p>
 */
class PostPersonalScopeVisibilityTest {

    private static final String OWNER = "owner@x.com";
    private static final String STRANGER = "stranger@x.com";
    private static final String GROUP = "grp-1";

    private PostRepo taskRepo;
    private GroupRepo groupRepo;
    private PostReactionService reactionService;
    private BlockService blockService;
    private PostService service;
    private PostReadAuthorizer authorizer;

    @BeforeEach
    void setUp() {
        taskRepo = mock(PostRepo.class);
        groupRepo = mock(GroupRepo.class);
        reactionService = mock(PostReactionService.class);
        blockService = mock(BlockService.class);
        authorizer = new PostReadAuthorizer(groupRepo);
        service = new PostService(
                taskRepo,
                mock(UserInfoRepo.class),
                mock(NominatimGeocodeService.class),
                mock(WebSocketMessageSender.class),
                mock(AlertModeService.class),
                mock(FollowRepo.class),
                blockService,
                reactionService,
                mock(PostCommentService.class),
                mock(StorageService.class),
                groupRepo,
                mock(PublisherPublishAuditService.class),
                mock(AgencyAuthorizationService.class),
                mock(PostConfirmRepo.class),
                mock(AskBookmarkRepo.class),
                mock(WorkOrderQuotaService.class),
                mock(AdminAuditLogService.class),
                mock(TaskAssigneeRepo.class),
                mock(TaskAssignmentService.class),
                mock(AgencyJurisdictionService.class),
                mock(CivicAgencyService.class),
                authorizer);
        // withEngagement dereferences the reaction summaries — real empties.
        when(reactionService.loadThankSummary(any(), any()))
                .thenReturn(new PostReactionService.ThankSummary(Map.of(), Set.of()));
        when(reactionService.loadReactionSummary(any(), any()))
                .thenReturn(new PostReactionService.ReactionSummary(Map.of(), Map.of()));
        when(blockService.getBlockSet(any())).thenReturn(Set.of());
    }

    // ---------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------

    private Post row(Long id, String kind, String groupId, String requester) {
        Post t = new Post();
        t.setId(id);
        t.setKind(kind);
        t.setGroupId(groupId);
        t.setRequesterEmail(requester);
        t.setStatus(PostStatus.OPEN);
        t.setTitle("Refill water");
        return t;
    }

    /** The real shape: personal task, groupless, and deliberately geo-less. */
    private Post personalTask() {
        return row(1L, "task", null, OWNER);
    }

    private Post communityAsk() {
        return row(2L, "ask", null, OWNER);
    }

    // ---------------------------------------------------------------
    // The rule itself
    // ---------------------------------------------------------------

    @Test
    void groupless_personalKinds_areReadableOnlyByTheirRequester() {
        for (String kind : List.of("task", "project")) {
            Post p = row(1L, kind, null, OWNER);
            assertTrue(authorizer.canRead(p, OWNER), kind + " must be readable by its author");
            assertFalse(authorizer.canRead(p, STRANGER), kind + " must be hidden from a stranger");
            assertFalse(authorizer.canRead(p, null), kind + " must be hidden from anonymous");
        }
    }

    @Test
    void requesterMatchIsCaseInsensitiveAndTrimmed() {
        Post p = personalTask();
        assertTrue(authorizer.canRead(p, "  OWNER@X.COM  "));
    }

    @Test
    void groupless_ordinaryCommunityKinds_stayPublic_includingAnonymous() {
        // The over-filtering guard. Civic reports and official agency posts
        // are the ones that would be most damaging to hide.
        for (String kind : List.of("ask", "offer", "post", "tip", "marketplace",
                "lost-found", "alert-update", "recommendation", "blog-promo",
                "civic-report", "official", "news")) {
            Post p = row(9L, kind, null, OWNER);
            assertTrue(authorizer.canRead(p, STRANGER), kind + " must stay public to any viewer");
            assertTrue(authorizer.canRead(p, null), kind + " must stay public to crawlers");
        }
    }

    @Test
    void unknownKind_isTreatedAsCommunityContent_notSilentlyHidden() {
        assertTrue(authorizer.canRead(row(9L, "some-future-kind", null, OWNER), STRANGER));
    }

    @Test
    void groupScoped_requiresMembership_andNeverLeaksToAnonymous() {
        Group g = new Group();
        g.setGroupId(GROUP);
        g.setOwnerEmail("boss@x.com");
        g.setMemberEmails(List.of(OWNER));
        when(groupRepo.findByGroupId(GROUP)).thenReturn(Optional.of(g));

        Post p = row(3L, "task", GROUP, OWNER);
        assertTrue(authorizer.canRead(p, OWNER), "member reads");
        assertTrue(authorizer.canRead(p, "boss@x.com"), "owner reads");
        assertFalse(authorizer.canRead(p, STRANGER), "non-member does not");
        assertFalse(authorizer.canRead(p, null), "anonymous does not");
    }

    @Test
    void anonymousGroupScopedRead_doesNotHitTheGroupRepo() {
        Post p = row(3L, "task", GROUP, OWNER);
        assertFalse(authorizer.canRead(p, null));
        verify(groupRepo, never()).findByGroupId(any());
    }

    // ---------------------------------------------------------------
    // Surface 1 — the community feed, at every radius
    // ---------------------------------------------------------------

    @Test
    void feed_withholdsPersonalTaskFromStranger_atEveryRadiusIncludingAnywhere() {
        when(taskRepo.findCommunityCandidates(any(), any()))
                .thenReturn(List.of(personalTask(), communityAsk()));

        // 10 km default, 250 mi top numeric rung, and the FE "anywhere"
        // sentinel. Geo-less rows bypass the distance test entirely, so the
        // radius must make no difference to the outcome.
        for (double radiusKm : new double[]{10, 402, 16000}) {
            List<PostDto> seen = service.discoverCommunity(
                    40.0, -111.0, radiusKm, Set.of(PostStatus.OPEN), STRANGER, 0, 50);
            assertTrue(seen.stream().noneMatch(d -> "task".equals(d.kind())),
                    "personal task leaked at radiusKm=" + radiusKm);
            assertTrue(seen.stream().anyMatch(d -> "ask".equals(d.kind())),
                    "community ask wrongly dropped at radiusKm=" + radiusKm);
        }
    }

    @Test
    void feed_stillShowsTheAuthorTheirOwnPersonalTask() {
        when(taskRepo.findCommunityCandidates(any(), any()))
                .thenReturn(List.of(personalTask()));
        List<PostDto> seen = service.discoverCommunity(
                40.0, -111.0, 10, Set.of(PostStatus.OPEN), OWNER, 0, 50);
        assertEquals(1, seen.size());
        assertEquals("task", seen.get(0).kind());
    }

    // ---------------------------------------------------------------
    // Surface 2 — /api/posts/by-author/{email}
    // ---------------------------------------------------------------

    @Test
    void byAuthor_withholdsPersonalTaskFromANonAuthor() {
        when(taskRepo.findByRequesterEmailIgnoreCaseOrderByCreatedAtDesc(OWNER))
                .thenReturn(List.of(personalTask(), communityAsk()));
        List<PostDto> seen = service.listRequestedBy(OWNER, STRANGER);
        assertTrue(seen.stream().noneMatch(d -> "task".equals(d.kind())));
        assertTrue(seen.stream().anyMatch(d -> "ask".equals(d.kind())));
    }

    /**
     * The /me/tasks regression guard. listRequestedBy(String) backs
     * {@code GET /api/me/posts?role=requester}; filtering it would empty the
     * personal task list for its own owner.
     */
    @Test
    void ownList_stillReturnsPersonalTasksToTheirOwner() {
        when(taskRepo.findByRequesterEmailIgnoreCaseOrderByCreatedAtDesc(OWNER))
                .thenReturn(List.of(personalTask(), communityAsk()));
        List<PostDto> mine = service.listRequestedBy(OWNER);
        assertEquals(2, mine.size(), "/me/tasks must still see the personal task");
        assertTrue(mine.stream().anyMatch(d -> "task".equals(d.kind())));
    }

    // ---------------------------------------------------------------
    // Surface 3 — the public profile feed
    // ---------------------------------------------------------------

    @Test
    void publicProfile_withholdsPersonalTaskFromAVisitor_butNotFromTheOwner() {
        when(taskRepo.findByRequesterEmailIgnoreCaseOrderByCreatedAtDesc(OWNER))
                .thenReturn(List.of(personalTask(), communityAsk()));

        List<PostDto> asVisitor = service.listPublicProfilePosts(OWNER, STRANGER, 10);
        assertTrue(asVisitor.stream().noneMatch(d -> "task".equals(d.kind())));
        assertTrue(asVisitor.stream().anyMatch(d -> "ask".equals(d.kind())));

        List<PostDto> asOwner = service.listPublicProfilePosts(OWNER, OWNER, 10);
        assertTrue(asOwner.stream().anyMatch(d -> "task".equals(d.kind())));
    }

    // ---------------------------------------------------------------
    // Surface 4 — the UNAUTHENTICATED share preview (OpenGraph tags)
    // ---------------------------------------------------------------

    @Test
    void sharePreview_emitsGenericCardForAPersonalTask_notTitleOrBody() {
        Post p = personalTask();
        p.setDescription("Dad's oxygen concentrator has no backup");
        when(taskRepo.findById(1L)).thenReturn(Optional.of(p));

        PostService.PostSharePreview preview = service.findPublicSharePreview(1L).orElseThrow();
        assertEquals("View this SitPrep post", preview.title());
        assertNull(preview.imageUrl());
        assertFalse(preview.title().contains("Refill water"), "title must not reach OG tags");
        assertFalse(preview.description().contains("oxygen"), "body must not reach OG tags");
        assertFalse(preview.description().contains(OWNER), "author must not reach OG tags");
    }

    @Test
    void sharePreview_stillUnfurlsAnOrdinaryCommunityPost() {
        when(taskRepo.findById(2L)).thenReturn(Optional.of(communityAsk()));
        PostService.PostSharePreview preview = service.findPublicSharePreview(2L).orElseThrow();
        assertTrue(preview.title().contains("Refill water"),
                "community posts must still unfurl — that is the feature");
    }

    @Test
    void sharePreview_keepsReturningGenericForGroupScopedRows() {
        when(taskRepo.findById(3L)).thenReturn(Optional.of(row(3L, "ask", GROUP, OWNER)));
        PostService.PostSharePreview preview = service.findPublicSharePreview(3L).orElseThrow();
        assertEquals("View this SitPrep post", preview.title());
    }
}
