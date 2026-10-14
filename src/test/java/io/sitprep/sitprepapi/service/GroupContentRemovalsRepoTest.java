package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.AdminAuditLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin-removal tombstone store (audit FD-06) against a real ORM (H2):
 * what {@link GroupContentRemovals} records is what its batched lookup reads
 * back, per kind, and nothing else.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({GroupContentRemovals.class, AdminAuditLogService.class})
class GroupContentRemovalsRepoTest {

    @Autowired private GroupContentRemovals removals;
    @Autowired private TestEntityManager em;

    private void auditRow(String action, String targetType, String targetId, Instant at) {
        AdminAuditLog row = new AdminAuditLog();
        row.setActorEmail("admin@x.com");
        row.setAction(action);
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setAt(at);
        em.persistAndFlush(row);
    }

    @Test
    void aRecordedRemovalReadsBackAndOthersDoNot() {
        removals.recordPost("Admin@X.com", 10L, "Removed: hello");
        removals.recordComment("admin@x.com", 77L, "Removed: rude");
        em.flush();

        Map<Long, Instant> posts = removals.removedPosts(List.of(10L, 11L));
        assertThat(posts).containsOnlyKeys(10L);
        assertThat(posts.get(10L)).isNotNull();
        assertThat(removals.isPostRemoved(10L)).isTrue();
        assertThat(removals.isPostRemoved(11L)).isFalse();

        // Kinds don't bleed: comment 77 is not post 77, post 10 is not comment 10.
        assertThat(removals.removedComments(List.of(77L, 10L))).containsOnlyKeys(77L);
        assertThat(removals.isPostRemoved(77L)).isFalse();
        assertThat(removals.isCommentRemoved(10L)).isFalse();
    }

    @Test
    void otherAuditActionsOnTheSameTargetAreNotRemovals() {
        auditRow("PINNED_GROUP_POST", GroupContentRemovals.POST_TARGET, "20", Instant.now());
        assertThat(removals.removedPosts(List.of(20L))).isEmpty();
    }

    @Test
    void twoRemovalsOfOnePostKeepTheEarliest() {
        Instant first = Instant.parse("2026-10-01T00:00:00Z");
        auditRow(GroupContentRemovals.POST_ACTION, GroupContentRemovals.POST_TARGET, "30",
                Instant.parse("2026-10-05T00:00:00Z"));
        auditRow(GroupContentRemovals.POST_ACTION, GroupContentRemovals.POST_TARGET, "30", first);

        assertThat(removals.removedPosts(List.of(30L))).containsExactly(Map.entry(30L, first));
        assertThat(removals.removedPosts(List.of())).isEmpty();
    }
}
