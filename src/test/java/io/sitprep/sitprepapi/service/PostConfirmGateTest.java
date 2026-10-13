package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.SystemAccounts;
import io.sitprep.sitprepapi.domain.AlertPost;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Post.PostStatus;
import io.sitprep.sitprepapi.domain.PostConfirm;
import io.sitprep.sitprepapi.repo.*;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * "I see it too" is a claim about the street right now (D8, owner ruling R7,
 * 2026-10-13). The endpoint used to check only that the id existed: anyone
 * could confirm a group post they cannot read and read the count back, and
 * SitPrep's own dispatched alerts, official posts and cancelled posts all
 * took confirms.
 */
class PostConfirmGateTest {

    private static final String VIEWER = "viewer@example.com";

    private PostRepo taskRepo;
    private GroupRepo groupRepo;
    private PostConfirmRepo confirmRepo;
    private AlertPostRepo alertPostRepo;
    private PostService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(PostRepo.class);
        groupRepo = mock(GroupRepo.class);
        confirmRepo = mock(PostConfirmRepo.class);
        alertPostRepo = mock(AlertPostRepo.class);
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
                confirmRepo,
                mock(PostFollowRepo.class),
                mock(AskBookmarkRepo.class),
                mock(WorkOrderQuotaService.class),
                mock(AdminAuditLogService.class),
                mock(TaskAssigneeRepo.class),
                mock(TaskAssignmentService.class),
                mock(AgencyJurisdictionService.class),
                mock(CivicAgencyService.class),
                new PostReadAuthorizer(groupRepo, mock(TaskAssigneeRepo.class)),
                mock(PostMentionService.class),
                alertPostRepo,
                mock(HazardReportRepo.class),
                mock(HazardVoteRepo.class),
                mock(io.sitprep.sitprepapi.gamification.TokenEventPublisher.class));
        when(confirmRepo.findByPostIdAndUserEmailIgnoreCase(anyLong(), anyString())).thenReturn(Optional.empty());
        when(alertPostRepo.findByPostIdIn(any())).thenReturn(List.of());
    }

    private Post post(long id, String kind, String author) {
        Post p = new Post();
        p.setId(id);
        p.setKind(kind);
        p.setRequesterEmail(author);
        p.setStatus(PostStatus.OPEN);
        when(taskRepo.findById(id)).thenReturn(Optional.of(p));
        when(taskRepo.existsById(id)).thenReturn(true);
        return p;
    }

    private static void assertStatus(HttpStatus expected, Runnable call) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, call::run);
        assertEquals(expected.value(), e.getStatusCode().value(), e.getReason());
    }

    // ── confirmable ───────────────────────────────────────────────────────

    @Test
    void aCivicReportTakesAConfirm() {
        post(1L, "civic-report", "reporter@example.com");
        when(confirmRepo.countByPostId(1L)).thenReturn(1L);

        PostService.ConfirmResult r = service.addConfirm(1L, VIEWER);

        assertEquals(1, r.confirmsCount());
        assertTrue(r.viewerConfirmed());
        verify(confirmRepo).saveAndFlush(any(PostConfirm.class));
    }

    @Test
    void aNeighboursAlertUpdateFollowUpTakesAConfirm() {
        post(2L, "alert-update", "neighbor@example.com");

        service.addConfirm(2L, VIEWER);

        verify(confirmRepo).saveAndFlush(any(PostConfirm.class));
    }

    // ── not confirmable (R7) ──────────────────────────────────────────────

    @Test
    void sitPrepsOwnDispatchedAlertIsNotConfirmable() {
        post(3L, "alert-update", SystemAccounts.SITPREP_EMAIL);

        assertStatus(HttpStatus.BAD_REQUEST, () -> service.addConfirm(3L, VIEWER));
        verify(confirmRepo, never()).saveAndFlush(any());
    }

    @Test
    void anAlertUpdateTrackedByTheDispatcherIsNotConfirmableWhoeverItsAuthorReads() {
        // The AlertPost row is the dispatcher's own record of the post — the
        // same row community.alertId is folded from, which is what the client
        // reads to make the same call.
        post(4L, "alert-update", "someone@example.com");
        AlertPost ap = new AlertPost();
        ap.setAlertId("NWS-urn:oid:watch");
        ap.setPostId(4L);
        when(alertPostRepo.findByPostIdIn(List.of(4L))).thenReturn(List.of(ap));

        assertStatus(HttpStatus.BAD_REQUEST, () -> service.addConfirm(4L, VIEWER));
        verify(confirmRepo, never()).saveAndFlush(any());
    }

    @Test
    void anOfficialPostAndAnOrdinaryPostAreNotConfirmable() {
        post(5L, "official", "agency@example.gov");
        post(6L, "post", "neighbor@example.com");

        assertStatus(HttpStatus.BAD_REQUEST, () -> service.addConfirm(5L, VIEWER));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.addConfirm(6L, VIEWER));
        verify(confirmRepo, never()).saveAndFlush(any());
    }

    @Test
    void aCancelledOrClosedPostTakesNoNewConfirm() {
        post(7L, "civic-report", "reporter@example.com").setStatus(PostStatus.CANCELLED);
        post(8L, "alert-update", "neighbor@example.com").setStatus(PostStatus.CLOSED);

        assertStatus(HttpStatus.CONFLICT, () -> service.addConfirm(7L, VIEWER));
        assertStatus(HttpStatus.CONFLICT, () -> service.addConfirm(8L, VIEWER));
        verify(confirmRepo, never()).saveAndFlush(any());
    }

    @Test
    void aPostPastItsEffectiveUntilTakesNoNewConfirm() {
        post(9L, "alert-update", "neighbor@example.com").setEffectiveUntil(Instant.now().minusSeconds(60));

        assertStatus(HttpStatus.CONFLICT, () -> service.addConfirm(9L, VIEWER));
    }

    // ── readability: 404, never 403 ───────────────────────────────────────

    @Test
    void aGroupPostTheViewerCannotReadIsA404AndLeaksNoCount() {
        Post p = post(10L, "civic-report", "member@example.com");
        p.setGroupId("g-private");
        Group g = new Group();
        g.setGroupId("g-private");
        g.setOwnerEmail("owner@example.com");
        g.setMemberEmails(List.of("member@example.com"));
        when(groupRepo.findByGroupId("g-private")).thenReturn(Optional.of(g));

        assertStatus(HttpStatus.NOT_FOUND, () -> service.addConfirm(10L, VIEWER));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.removeConfirm(10L, VIEWER));
        verify(confirmRepo, never()).countByPostId(anyLong());
        verify(confirmRepo, never()).deleteByPostAndUser(anyLong(), anyString());
    }

    @Test
    void anUnknownPostIsTheSame404() {
        when(taskRepo.findById(99L)).thenReturn(Optional.empty());

        assertStatus(HttpStatus.NOT_FOUND, () -> service.addConfirm(99L, VIEWER));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.removeConfirm(99L, VIEWER));
    }

    // ── removing your own confirm ─────────────────────────────────────────

    @Test
    void aViewerCanAlwaysTakeBackTheirOwnConfirmOnAPostTheyCanRead() {
        // Confirmed before the gate (or before the post ended): the count is
        // theirs to withdraw, whatever the post has become since.
        post(11L, "alert-update", SystemAccounts.SITPREP_EMAIL).setStatus(PostStatus.CANCELLED);

        PostService.ConfirmResult r = service.removeConfirm(11L, VIEWER);

        assertFalse(r.viewerConfirmed());
        verify(confirmRepo).deleteByPostAndUser(11L, VIEWER);
    }
}
