package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.AdminAuditLog;
import io.sitprep.sitprepapi.repo.AdminAuditLogRepo;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Admin removal of group content (audit FD-06, decision D24: every tier).
 *
 * <p>The removal record IS the {@code admin_audit_log} row: who removed it,
 * when, and a preview of what was removed. The content row itself stays (a
 * soft delete) with its text, image and place cleared, and every DTO built
 * for it reads {@code removedByAdmin} from here, so the feed shows a
 * "Removed by an admin" tombstone in place of the post instead of a gap.</p>
 *
 * <p>Why the audit row and not a column: a new column needs a Flyway
 * migration, and this lane must not take a version number while V107
 * (alerts) and V108 (B2) are unapplied (Flyway runs without outOfOrder, so a
 * higher version landing first fails their boot). The lookup is one indexed
 * query per page ({@code idx_audit_target} on target_type, target_id).</p>
 */
@Component
public class GroupContentRemovals {

    public static final String POST_TARGET = "group_post";
    public static final String COMMENT_TARGET = "group_post_comment";
    public static final String POST_ACTION = "REMOVED_GROUP_POST";
    public static final String COMMENT_ACTION = "REMOVED_GROUP_COMMENT";

    private final AdminAuditLogRepo repo;
    private final AdminAuditLogService audit;

    public GroupContentRemovals(AdminAuditLogRepo repo, AdminAuditLogService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    public void recordPost(String actorEmail, Long postId, String summary) {
        audit.record(actorEmail, POST_ACTION, POST_TARGET, String.valueOf(postId), summary);
    }

    public void recordComment(String actorEmail, Long commentId, String summary) {
        audit.record(actorEmail, COMMENT_ACTION, COMMENT_TARGET, String.valueOf(commentId), summary);
    }

    /** Removal moment per removed post id; ids absent from the map were not removed. */
    public Map<Long, Instant> removedPosts(Collection<Long> postIds) {
        return lookup(POST_TARGET, POST_ACTION, postIds);
    }

    /** Removal moment per removed comment id. */
    public Map<Long, Instant> removedComments(Collection<Long> commentIds) {
        return lookup(COMMENT_TARGET, COMMENT_ACTION, commentIds);
    }

    public boolean isPostRemoved(Long postId) {
        return postId != null && removedPosts(List.of(postId)).containsKey(postId);
    }

    public boolean isCommentRemoved(Long commentId) {
        return commentId != null && removedComments(List.of(commentId)).containsKey(commentId);
    }

    private Map<Long, Instant> lookup(String targetType, String action, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        List<String> keys = ids.stream().filter(Objects::nonNull).distinct().map(String::valueOf).toList();
        if (keys.isEmpty()) return Map.of();
        List<AdminAuditLog> rows = repo.findByTargetTypeAndActionAndTargetIdIn(targetType, action, keys);
        if (rows == null || rows.isEmpty()) return Map.of();
        Map<Long, Instant> out = new HashMap<>();
        for (AdminAuditLog r : rows) {
            try {
                out.merge(Long.valueOf(r.getTargetId()), r.getAt() == null ? Instant.EPOCH : r.getAt(),
                        (a, b) -> a.isBefore(b) ? a : b);
            } catch (NumberFormatException ignored) {
                // a malformed target id is not a removal of anything we list
            }
        }
        return out;
    }
}
