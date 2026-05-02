package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.PostCommentReaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link PostCommentReaction}. Mirrors {@code PostReactionRepo}
 * with the FK column swapped — same finder shapes, same batched-load
 * convention, same idempotent add-or-no-op semantics.
 */
@Repository
public interface PostCommentReactionRepo extends JpaRepository<PostCommentReaction, Long> {

    List<PostCommentReaction> findByPostCommentId(Long postCommentId);

    /** Batched roster fetch for a list of comments (thread listing). */
    List<PostCommentReaction> findByPostCommentIdIn(Collection<Long> postCommentIds);

    /** Used to detect "already reacted" so add-twice is a no-op. */
    Optional<PostCommentReaction> findByPostCommentIdAndUserEmailIgnoreCaseAndEmoji(
            Long postCommentId, String userEmail, String emoji);

    /**
     * Did this viewer react with this emoji on any of the supplied comments?
     * Used by the listing path to populate {@code viewerThanked} per row
     * in one query rather than N separate lookups.
     */
    @Query("SELECT r.postCommentId FROM PostCommentReaction r " +
           "WHERE r.postCommentId IN :postCommentIds " +
           "AND lower(r.userEmail) = lower(:userEmail) " +
           "AND r.emoji = :emoji")
    List<Long> findPostCommentIdsWhereViewerReacted(
            @Param("postCommentIds") Collection<Long> postCommentIds,
            @Param("userEmail") String userEmail,
            @Param("emoji") String emoji);

    @Modifying
    @Query("DELETE FROM PostCommentReaction r WHERE r.postCommentId = :postCommentId " +
           "AND lower(r.userEmail) = lower(:userEmail) AND r.emoji = :emoji")
    int deleteByPostCommentUserEmoji(@Param("postCommentId") Long postCommentId,
                                     @Param("userEmail") String userEmail,
                                     @Param("emoji") String emoji);

    @Modifying
    @Query("DELETE FROM PostCommentReaction r WHERE r.postCommentId = :postCommentId")
    void deleteAllByPostCommentId(@Param("postCommentId") Long postCommentId);
}
