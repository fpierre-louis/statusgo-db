package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Post.PostStatus;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.TaskAssigneeRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@code PATCH /api/posts/{id}?workDetails=merge} (lane B6, for F8's
 * EditWorkOrderModal lost update): two edits to different keys both survive;
 * an explicit null removes a key; without the flag the bag is still replaced.
 */
class PostWorkDetailsMergeTest {

    private static final String AUTHOR = "owner@x.com";
    private PostRepo taskRepo;
    private PostService service;
    private Post stored;

    @BeforeEach
    void setUp() {
        taskRepo = mock(PostRepo.class);
        service = PostServiceFixture.build(taskRepo, mock(GroupRepo.class), mock(TaskAssigneeRepo.class),
                mock(WorkOrderQuotaService.class), mock(TaskAssignmentService.class));
        stored = new Post();
        stored.setId(5L);
        stored.setKind("task");
        stored.setTitle("Clear the culvert");
        stored.setDescription("Debris after the storm");
        stored.setRequesterEmail(AUTHOR);
        stored.setStatus(PostStatus.OPEN);
        stored.setPriority(Post.PostPriority.MEDIUM);
        stored.setCreatedAt(Instant.now());
        Map<String, Object> wd = new LinkedHashMap<>();
        wd.put("address", "1 Main St");
        wd.put("crewSize", 2);
        wd.put("notes", "bring gloves");
        stored.setWorkDetails(wd);
        when(taskRepo.findById(5L)).thenReturn(Optional.of(stored));
        when(taskRepo.save(any(Post.class))).thenAnswer(inv -> inv.getArgument(0));
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static Post bag(Map<String, Object> wd) {
        Post p = new Post();
        p.setWorkDetails(wd);
        return p;
    }

    @Test
    void twoMergeEditsToDifferentKeysBothSurvive() {
        // Editor A opened the modal and changed the address only...
        service.patch(5L, bag(new HashMap<>(Map.of("address", "9 Elm St"))), AUTHOR, true);
        // ...editor B, from a stale copy, changed the crew size only.
        service.patch(5L, bag(new HashMap<>(Map.of("crewSize", 4))), AUTHOR, true);

        assertThat(stored.getWorkDetails())
                .containsEntry("address", "9 Elm St")
                .containsEntry("crewSize", 4)
                .containsEntry("notes", "bring gloves");
    }

    @Test
    void anExplicitNullRemovesTheKey() {
        Map<String, Object> sent = new HashMap<>();
        sent.put("notes", null);
        service.patch(5L, bag(sent), AUTHOR, true);
        assertThat(stored.getWorkDetails()).doesNotContainKey("notes").containsEntry("crewSize", 2);
    }

    @Test
    void withoutTheFlagTheBagIsStillReplaced() {
        service.patch(5L, bag(new HashMap<>(Map.of("address", "9 Elm St"))), AUTHOR);
        assertThat(stored.getWorkDetails()).containsEntry("address", "9 Elm St")
                .doesNotContainKey("crewSize").doesNotContainKey("notes");
    }
}
