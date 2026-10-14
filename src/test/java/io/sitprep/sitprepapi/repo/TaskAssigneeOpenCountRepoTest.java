package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.TaskAssignee;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-17 (B4 review, 2026-10-14): the circle card's "N tasks for you" is one
 * aggregate query on a real ORM (H2; the full context, whose H2 setup the
 * {@code task} table's jsonb columns need). It counts only open, non-project tasks
 * in the asked groups, once per task whatever the viewer's roles on it, and
 * matches the email case-insensitively.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TaskAssigneeOpenCountRepoTest {

    private static final Set<Post.PostStatus> TERMINAL = EnumSet.of(
            Post.PostStatus.DONE, Post.PostStatus.CLOSED, Post.PostStatus.CANCELLED, Post.PostStatus.ARCHIVED);

    @Autowired private TaskAssigneeRepo repo;
    @Autowired private PostRepo posts;

    private Long task(String groupId, String kind, Post.PostStatus status) {
        Post p = new Post();
        p.setGroupId(groupId);
        p.setKind(kind);
        p.setStatus(status);
        p.setRequesterEmail("boss@x.com");
        p.setTitle("t");
        p.setDescription("d");
        return posts.saveAndFlush(p).getId();
    }

    private void assign(Long taskId, String email, TaskAssignee.Role role) {
        TaskAssignee a = new TaskAssignee();
        a.setPostId(taskId);
        a.setEmail(email);
        a.setRole(role);
        repo.saveAndFlush(a);
    }

    @Test
    void countsOpenNonProjectTasksPerAskedGroup() {
        assign(task("g1", "task", Post.PostStatus.OPEN), "me@x.com", TaskAssignee.Role.LEAD);
        assign(task("g1", "task", Post.PostStatus.IN_PROGRESS), "me@x.com", TaskAssignee.Role.HELPER);
        assign(task("g1", "task", Post.PostStatus.DONE), "me@x.com", TaskAssignee.Role.LEAD);       // terminal
        assign(task("g1", "project", Post.PostStatus.OPEN), "me@x.com", TaskAssignee.Role.LEAD);    // a container
        assign(task("g2", "task", Post.PostStatus.OPEN), "me@x.com", TaskAssignee.Role.LEAD);
        assign(task("elsewhere", "task", Post.PostStatus.OPEN), "me@x.com", TaskAssignee.Role.LEAD); // not asked
        assign(task("g2", "task", Post.PostStatus.OPEN), "someone@x.com", TaskAssignee.Role.LEAD);  // not mine

        Map<String, Long> out = toMap(repo.countOpenAssignedByGroup("Me@X.com", Set.of("g1", "g2", "g3"), TERMINAL));

        assertThat(out).containsExactlyInAnyOrderEntriesOf(Map.of("g1", 2L, "g2", 1L));
    }

    private static Map<String, Long> toMap(List<Object[]> rows) {
        Map<String, Long> m = new HashMap<>();
        for (Object[] r : rows) m.put((String) r[0], ((Number) r[1]).longValue());
        return m;
    }
}
