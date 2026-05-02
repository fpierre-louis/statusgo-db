package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.GroupPostCommentReaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link GroupPostCommentReaction}. Mirrors
 * {@code PostCommentReactionRepo} with the FK column swapped — same
 * finder shapes, same batched-load convention.
 */
@Repository
public interface GroupPostCommentReactionRepo extends JpaRepository<GroupPostCommentReaction, Long> {

    List<GroupPostCommentReaction> findByGroupPostCommentId(Long groupPostCommentId);

    /** Batched roster fetch for a list of comments (thread listing). */
    List<GroupPostCommentReaction> findByGroupPostCommentIdIn(Collection<Long> groupPostCommentIds);

    /** Used to detect "already reacted" so add-twice is a no-op. */
    Optional<GroupPostCommentReaction> findByGroupPostCommentIdAndUserEmailIgnoreCaseAndEmoji(
            Long groupPostCommentId, String userEmail, String emoji);

    /**
     * Did this viewer react with this emoji on any of the supplied
     * comments? Used by the listing path to populate
     * {@code viewerThanked} per row in one query rather than N
     * separate lookups.
     */
    @Query("SELECT r.groupPostCommentId FROM GroupPostCommentReaction r " +
           "WHERE r.groupPostCommentId IN :groupPostCommentIds " +
           "AND lower(r.userEmail) = lower(:userEmail) " +
           "AND r.emoji = :emoji")
    List<Long> findGroupPostCommentIdsWhereViewerReacted(
            @Param("groupPostCommentIds") Collection<Long> groupPostCommentIds,
            @Param("userEmail") String userEmail,
            @Param("emoji") String emoji);

    @Modifying
    @Query("DELETE FROM GroupPostCommentReaction r " +
           "WHERE r.groupPostCommentId = :groupPostCommentId " +
           "AND lower(r.userEmail) = lower(:userEmail) AND r.emoji = :emoji")
    int deleteByGroupPostCommentUserEmoji(
            @Param("groupPostCommentId") Long groupPostCommentId,
            @Param("userEmail") String userEmail,
            @Param("emoji") String emoji);

    @Modifying
    @Query("DELETE FROM GroupPostCommentReaction r " +
           "WHERE r.groupPostCommentId = :groupPostCommentId")
    void deleteAllByGroupPostCommentId(@Param("groupPostCommentId") Long groupPostCommentId);
}
