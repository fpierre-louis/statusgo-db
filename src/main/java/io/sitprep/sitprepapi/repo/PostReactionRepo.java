package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.PostReaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link PostReaction}. Mirrors {@code GroupPostReactionRepo} so
 * future unification is mechanical.
 */
@Repository
public interface PostReactionRepo extends JpaRepository<PostReaction, Long> {

    List<PostReaction> findByPostId(Long postId);

    /** Batched roster fetch for a list of tasks (community feed listing). */
    List<PostReaction> findByPostIdIn(Collection<Long> postIds);

    /** Used to detect "already reacted" so add-twice is a no-op. */
    Optional<PostReaction> findByPostIdAndUserEmailIgnoreCaseAndEmoji(
            Long postId, String userEmail, String emoji);

    /**
     * Did this viewer react with this emoji on any of the supplied tasks?
     * Used by the listing path to populate {@code viewerThanked} per row
     * in one query rather than N separate lookups.
     */
    @Query("SELECT r.postId FROM PostReaction r " +
           "WHERE r.postId IN :postIds " +
           "AND lower(r.userEmail) = lower(:userEmail) " +
           "AND r.emoji = :emoji")
    List<Long> findPostIdsWhereViewerReacted(@Param("postIds") Collection<Long> postIds,
                                             @Param("userEmail") String userEmail,
                                             @Param("emoji") String emoji);

    @Modifying
    @Query("DELETE FROM PostReaction r WHERE r.postId = :postId " +
           "AND lower(r.userEmail) = lower(:userEmail) AND r.emoji = :emoji")
    int deleteByPostUserEmoji(@Param("postId") Long postId,
                              @Param("userEmail") String userEmail,
                              @Param("emoji") String emoji);

    @Modifying
    @Query("DELETE FROM PostReaction r WHERE r.postId = :postId")
    void deleteAllByPostId(@Param("postId") Long postId);
}
