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
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Audit TK-02 (P0) + owner decision D9, and TK-08's create half.
 *
 * <p>Create forwarded {@code assigneeEmail} to {@code addLead} with no member
 * check, so anyone could create a group and push "New work assignment" with
 * their own text to any registered account. {@code /assign} already checked;
 * create now runs the same {@link PostService#requireAssignableMember}. D9:
 * a group-scoped kind=task is created by the group's owner or admins only.
 * TK-08: a new row is always OPEN, whatever status the client sent.</p>
 */
class PostCreateAssigneeTest {

    private static final String GROUP = "grp-1";
    private static final String OWNER = "owner@x.com";
    private static final String ADMIN = "admin@x.com";
    private static final String MEMBER = "member@x.com";

    private PostRepo taskRepo;
    private TaskAssignmentService assignments;
    private WorkOrderQuotaService quota;
    private PostService service;

    /** Thrown from save() so a test can inspect the row create built, without the post-save folds. */
    private static final class Saved extends RuntimeException {}

    @BeforeEach
    void setUp() {
        taskRepo = mock(PostRepo.class);
        GroupRepo groupRepo = mock(GroupRepo.class);
        assignments = mock(TaskAssignmentService.class);
        quota = mock(WorkOrderQuotaService.class);
        Group g = new Group();
        g.setGroupId(GROUP);
        g.setOwnerEmail(OWNER);
        g.setAdminEmails(List.of(ADMIN));
        g.setMemberEmails(List.of(MEMBER));
        g.setPendingMemberEmails(List.of("pending@x.com"));
        when(groupRepo.findByGroupId(GROUP)).thenReturn(Optional.of(g));
        service = PostServiceFixture.build(taskRepo, groupRepo, mock(TaskAssigneeRepo.class),
                quota, assignments);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static Post workOrder(String assignee) {
        Post p = new Post();
        p.setGroupId(GROUP);
        p.setKind("task");
        p.setTitle("Sandbag the north wall");
        p.setDescription("Before the river crests");
        p.setAssigneeEmail(assignee);
        return p;
    }

    @Test
    void outsiderAssignee_is403_andNothingIsSavedOrPushed() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.create(workOrder("victim@elsewhere.com"), OWNER));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(taskRepo, never()).save(any(Post.class));
        verifyNoInteractions(assignments);
    }

    @Test
    void pendingMemberAssignee_is403() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.create(workOrder("pending@x.com"), ADMIN));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(taskRepo, never()).save(any(Post.class));
    }

    @Test
    void plainMemberCreatingAGroupWorkOrder_is403() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.create(workOrder(null), MEMBER));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(taskRepo, never()).save(any(Post.class));
    }

    @Test
    void memberAssignee_passes_andClientStatusIsIgnored() {
        when(taskRepo.save(any(Post.class))).thenThrow(new Saved());
        Post incoming = workOrder(MEMBER);
        incoming.setStatus(PostStatus.DONE);

        assertThrows(Saved.class, () -> service.create(incoming, ADMIN));

        ArgumentCaptor<Post> row = ArgumentCaptor.forClass(Post.class);
        verify(taskRepo).save(row.capture());
        assertEquals(PostStatus.OPEN, row.getValue().getStatus());
        assertEquals(GROUP, row.getValue().getGroupId());
        // The quota is asked with the server-derived life-safety flag.
        verify(quota).assertQuota(any(Group.class), eq(false), any());
    }

    @Test
    void doNotEnterSite_isLifeSafety_forTheQuota() {
        // TK-07: the quota reads the ONE life-safety rule (a flag OR a
        // do-not-enter site), not the flags alone.
        when(taskRepo.save(any(Post.class))).thenThrow(new Saved());
        Post incoming = workOrder(null);
        incoming.setWorkDetails(new java.util.HashMap<>(java.util.Map.of("siteSafeToEnter", "unsafeDoNotEnter")));

        assertThrows(Saved.class, () -> service.create(incoming, ADMIN));

        verify(quota).assertQuota(any(Group.class), eq(true), any());
    }
}
