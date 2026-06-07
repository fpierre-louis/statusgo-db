package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.GroupPost;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface GroupPostRepo extends JpaRepository<GroupPost, Long> {

    /**
     * Atomic counter adjust for {@code commentsCount}. Replaces the
     * read-modify-write pattern that drifted under concurrent comment
     * create/delete (audit DB-02). The {@code COALESCE} guards against
     * the legacy NULL rows that predate the {@code default 0} migration.
     * Returns the number of rows updated (0 if the post was deleted
     * between the comment write and this UPDATE — safe to ignore).
     */
    @Modifying
    @Query("UPDATE GroupPost p " +
           "SET p.commentsCount = COALESCE(p.commentsCount, 0) + :delta " +
           "WHERE p.id = :id")
    int adjustCommentsCount(@Param("id") Long id, @Param("delta") int delta);

    @Query("SELECT p FROM GroupPost p WHERE p.groupId = :groupId ORDER BY p.timestamp DESC")
    List<GroupPost> findPostsByGroupId(@Param("groupId") String groupId);

    // Backfill by UPDATED time (ascending for client merge)
    List<GroupPost> findByGroupIdAndUpdatedAtAfterOrderByUpdatedAtAsc(String groupId, Instant since);

    @Query("""
           SELECT p FROM GroupPost p
           WHERE p.groupId IN :groupIds
             AND p.timestamp = (
                 SELECT MAX(p2.timestamp) FROM GroupPost p2 WHERE p2.groupId = p.groupId
             )
           """)
    List<GroupPost> findLatestPostsByGroupIds(@Param("groupIds") List<String> groupIds);

    /**
     * All pinned posts for a group, newest-pin first. Used by the
     * paginated listing path so pinned content always appears on every
     * page-1 fetch regardless of how far back the user has scrolled
     * through unpinned history. Cardinality is bounded — admins
     * typically pin 0-3 posts per group, so this stays small.
     */
    @Query("""
           SELECT p FROM GroupPost p
           WHERE p.groupId = :groupId
             AND p.pinnedAt IS NOT NULL
           ORDER BY p.pinnedAt DESC
           """)
    List<GroupPost> findPinnedByGroupId(@Param("groupId") String groupId);

    /**
     * Cursor-paginated page of UNPINNED posts ordered by id DESC (id
     * is monotonic so it doubles as a stable created-at cursor).
     *
     * <p>When {@code before} is null, returns the latest page; when
     * {@code before} is a non-null id, returns the next page of
     * unpinned posts older than that id. The pinned set is fetched
     * separately via {@link #findPinnedByGroupId(String)} so paginated
     * scroll never accidentally hides a pin that lives in older
     * history.</p>
     *
     * <p>The COALESCE pattern lets the same query handle both the
     * first-page (no cursor) and subsequent-page (with cursor) cases
     * without two methods. {@code Long.MAX_VALUE} on null is the
     * "include everything older than infinity" defensive default.</p>
     */
    @Query("""
           SELECT p FROM GroupPost p
           WHERE p.groupId = :groupId
             AND p.pinnedAt IS NULL
             AND p.id < COALESCE(:before, 9223372036854775807L)
           ORDER BY p.id DESC
           """)
    List<GroupPost> findUnpinnedByGroupIdPage(@Param("groupId") String groupId,
                                              @Param("before") Long before,
                                              Pageable pageable);

    /**
     * Per-group unread count for the Circles list page. Counts posts in
     * the group newer than the viewer's {@code GroupReadState.lastReadAt}.
     * Spring Data derives the query from the method name; the existing
     * indexes on {@code (group_id, timestamp)} keep it fast.
     */
    int countByGroupIdAndTimestampAfter(String groupId, Instant timestamp);
}
