package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Post.PostStatus;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.exception.LiabilityNotAcceptedException;
import io.sitprep.sitprepapi.repo.AskBookmarkRepo;
import io.sitprep.sitprepapi.repo.FollowRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostConfirmRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * State-machine liability gate (Phase 2): a work order with
 * {@code liabilityRequired=true} cannot advance into IN_PROGRESS / DONE (etc.)
 * until a release is captured — {@code releaseSigned} OR a
 * {@code releaseExceptionReason}. The guard throws
 * {@link LiabilityNotAcceptedException} (→ HTTP 409). The
 * {@code POST /release} service call persists the acceptance and unblocks the
 * transition. Pure Mockito — same-package services need no import.
 */
class PostLiabilityGateTest {

    private static final Long POST_ID = 42L;
    private static final String REQUESTER = "requester@x.com";

    private PostRepo taskRepo;
    private PostService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(PostRepo.class);
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
                mock(GroupRepo.class),
                mock(PublisherPublishAuditService.class),
                mock(AgencyAuthorizationService.class),
                mock(PostConfirmRepo.class),
                mock(AskBookmarkRepo.class),
                mock(WorkOrderQuotaService.class),
                mock(AdminAuditLogService.class));
        // refetchAndBroadcast registers an afterCommit synchronization on the
        // successful transition path — same pattern as GroupPostSecurityTest.
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private Post gatedTask(boolean releaseSigned, String exceptionReason) {
        Post t = new Post();
        t.setId(POST_ID);
        t.setRequesterEmail(REQUESTER);
        t.setStatus(PostStatus.OPEN);
        t.setKind("task");
        t.setLiabilityRequired(true);
        t.setReleaseSigned(releaseSigned);
        t.setReleaseExceptionReason(exceptionReason);
        return t;
    }

    // -----------------------------------------------------------------
    // 409 — gated task cannot advance without a captured release
    // -----------------------------------------------------------------

    @Test
    void markInProgress_onGatedUnsignedTask_throws409_andNeverTransitions() {
        when(taskRepo.findById(POST_ID)).thenReturn(Optional.of(gatedTask(false, null)));

        assertThrows(LiabilityNotAcceptedException.class,
                () -> service.markInProgress(POST_ID));

        // The guard blocks BEFORE the atomic DB transition is attempted.
        verify(taskRepo, never()).transitionToInProgress(anyLong(), any(), any());
    }

    @Test
    void complete_onGatedUnsignedTask_throws409() {
        when(taskRepo.findById(POST_ID)).thenReturn(Optional.of(gatedTask(false, null)));

        assertThrows(LiabilityNotAcceptedException.class,
                () -> service.complete(POST_ID));

        verify(taskRepo, never()).transitionComplete(anyLong(), any(), any(), any());
    }

    // -----------------------------------------------------------------
    // /release — persists acceptance, validates, and unblocks
    // -----------------------------------------------------------------

    @Test
    void acceptRelease_signed_persistsReleaseFields() {
        Post t = gatedTask(false, null);
        when(taskRepo.findById(POST_ID)).thenReturn(Optional.of(t));
        when(taskRepo.save(any(Post.class))).thenAnswer(inv -> inv.getArgument(0));

        service.acceptRelease(POST_ID, true, "sha256hash", null);

        ArgumentCaptor<Post> saved = ArgumentCaptor.forClass(Post.class);
        verify(taskRepo).save(saved.capture());
        assertTrue(saved.getValue().isReleaseSigned());
        assertEquals("sha256hash", saved.getValue().getReleaseTextHash());
    }

    @Test
    void acceptRelease_unsignedWithNoReason_throws400() {
        when(taskRepo.findById(POST_ID)).thenReturn(Optional.of(gatedTask(false, null)));

        assertThrows(IllegalArgumentException.class,
                () -> service.acceptRelease(POST_ID, false, null, null));
        verify(taskRepo, never()).save(any());
    }

    @Test
    void acceptRelease_didNotSignWithReason_persistsException() {
        Post t = gatedTask(false, null);
        when(taskRepo.findById(POST_ID)).thenReturn(Optional.of(t));
        when(taskRepo.save(any(Post.class))).thenAnswer(inv -> inv.getArgument(0));

        service.acceptRelease(POST_ID, false, null, "requester absent — language barrier");

        ArgumentCaptor<Post> saved = ArgumentCaptor.forClass(Post.class);
        verify(taskRepo).save(saved.capture());
        assertFalse(saved.getValue().isReleaseSigned());
        assertEquals("requester absent — language barrier",
                saved.getValue().getReleaseExceptionReason());
    }

    @Test
    void afterReleaseSigned_markInProgress_isUnblocked() {
        // The same task, now signed, sails past the guard into the transition.
        Post signed = gatedTask(true, null);
        when(taskRepo.findById(POST_ID)).thenReturn(Optional.of(signed));
        when(taskRepo.transitionToInProgress(eq(POST_ID), any(), eq(PostStatus.IN_PROGRESS)))
                .thenReturn(1);

        PostDto dto = assertDoesNotThrow(() -> service.markInProgress(POST_ID));

        assertNotNull(dto);
        verify(taskRepo).transitionToInProgress(eq(POST_ID), any(), eq(PostStatus.IN_PROGRESS));
    }

    @Test
    void nonGatedTask_advancesFreely() {
        Post ungated = gatedTask(false, null);
        ungated.setLiabilityRequired(false); // not gated
        when(taskRepo.findById(POST_ID)).thenReturn(Optional.of(ungated));
        when(taskRepo.transitionToInProgress(eq(POST_ID), any(), eq(PostStatus.IN_PROGRESS)))
                .thenReturn(1);

        assertDoesNotThrow(() -> service.markInProgress(POST_ID));
    }
}
