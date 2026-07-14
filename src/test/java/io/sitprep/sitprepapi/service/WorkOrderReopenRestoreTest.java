package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Post.PostStatus;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Lifecycle reopen/restore (work-order overhaul, Phase B). The status-aware
 * reopen sends a DONE task back to IN_PROGRESS and a CANCELLED task to OPEN;
 * restore pulls an ARCHIVED task back to OPEN. All route through
 * {@code transitionReopen}, which also NULLs {@code completedAt} to reset the
 * archive clock. Pure Mockito — same-package service needs no import.
 */
class WorkOrderReopenRestoreTest {

    private static final Long POST_ID = 77L;

    private PostRepo taskRepo;
    private AdminAuditLogService audit;
    private PostService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(PostRepo.class);
        audit = mock(AdminAuditLogService.class);
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
                audit,
                mock(io.sitprep.sitprepapi.repo.TaskAssigneeRepo.class),
                mock(TaskAssignmentService.class));
        // refetchAndBroadcast registers an afterCommit synchronization.
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private Post taskWithStatus(PostStatus status) {
        Post t = new Post();
        t.setId(POST_ID);
        t.setKind("task");
        t.setStatus(status);
        return t;
    }

    @Test
    void reopen_doneTask_goesToInProgress() {
        when(taskRepo.findById(POST_ID)).thenReturn(Optional.of(taskWithStatus(PostStatus.DONE)));
        when(taskRepo.transitionReopen(eq(POST_ID), eq(PostStatus.DONE), eq(PostStatus.IN_PROGRESS)))
                .thenReturn(1);

        assertDoesNotThrow(() -> service.reopen(POST_ID));

        verify(taskRepo).transitionReopen(eq(POST_ID), eq(PostStatus.DONE), eq(PostStatus.IN_PROGRESS));
        verify(taskRepo, never()).transitionReopen(eq(POST_ID), eq(PostStatus.CANCELLED), eq(PostStatus.OPEN));
        // Backward move must be persistently audited (Guardrail 1).
        verify(audit).record(any(), eq("task.reopen"), eq("task"), eq(String.valueOf(POST_ID)), any());
    }

    @Test
    void reopen_cancelledTask_goesToOpen() {
        when(taskRepo.findById(POST_ID)).thenReturn(Optional.of(taskWithStatus(PostStatus.CANCELLED)));
        when(taskRepo.transitionReopen(eq(POST_ID), eq(PostStatus.CANCELLED), eq(PostStatus.OPEN)))
                .thenReturn(1);

        assertDoesNotThrow(() -> service.reopen(POST_ID));

        verify(taskRepo).transitionReopen(eq(POST_ID), eq(PostStatus.CANCELLED), eq(PostStatus.OPEN));
    }

    @Test
    void reopen_whenNothingMatches_throws() {
        // e.g. an OPEN task: the CANCELLED→OPEN branch matches 0 rows.
        when(taskRepo.findById(POST_ID)).thenReturn(Optional.of(taskWithStatus(PostStatus.OPEN)));
        when(taskRepo.transitionReopen(eq(POST_ID), eq(PostStatus.CANCELLED), eq(PostStatus.OPEN)))
                .thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.reopen(POST_ID));
    }

    @Test
    void restore_archivedTask_goesToOpen() {
        when(taskRepo.findById(POST_ID)).thenReturn(Optional.of(taskWithStatus(PostStatus.ARCHIVED)));
        when(taskRepo.transitionReopen(eq(POST_ID), eq(PostStatus.ARCHIVED), eq(PostStatus.OPEN)))
                .thenReturn(1);

        assertDoesNotThrow(() -> service.restore(POST_ID));

        verify(taskRepo).transitionReopen(eq(POST_ID), eq(PostStatus.ARCHIVED), eq(PostStatus.OPEN));
        verify(audit).record(any(), eq("task.restore"), eq("task"), eq(String.valueOf(POST_ID)), any());
    }

    @Test
    void reopen_failure_writesNoAudit() {
        // A no-op transition (0 rows) must not leave a misleading audit trail.
        when(taskRepo.findById(POST_ID)).thenReturn(Optional.of(taskWithStatus(PostStatus.OPEN)));
        when(taskRepo.transitionReopen(eq(POST_ID), eq(PostStatus.CANCELLED), eq(PostStatus.OPEN)))
                .thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.reopen(POST_ID));

        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void restore_whenNotArchived_throws() {
        when(taskRepo.findById(POST_ID)).thenReturn(Optional.of(taskWithStatus(PostStatus.DONE)));
        when(taskRepo.transitionReopen(eq(POST_ID), eq(PostStatus.ARCHIVED), eq(PostStatus.OPEN)))
                .thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.restore(POST_ID));
    }
}
