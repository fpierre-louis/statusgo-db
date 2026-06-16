package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.PostConfirm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link PostConfirm}. Mirrors {@link PostReactionRepo} so the
 * count + viewer-state folding in PostConfirmService matches reactions.
 */
@Repository
public interface PostConfirmRepo extends JpaRepository<PostConfirm, Long> {

    /** Detect "already confirmed" so add-twice is a no-op. */
    Optional<PostConfirm> findByPostIdAndUserEmailIgnoreCase(Long postId, String userEmail);

    long countByPostId(Long postId);

    /** Batched fetch for a list of tasks (community feed listing) — folded in-memory. */
    List<PostConfirm> findByPostIdIn(Collection<Long> postIds);

    /**
     * Which of the supplied tasks has THIS viewer confirmed? One query to
     * populate {@code viewerConfirmed} per row rather than N lookups.
     */
    @Query("SELECT c.postId FROM PostConfirm c " +
           "WHERE c.postId IN :postIds " +
           "AND lower(c.userEmail) = lower(:userEmail)")
    List<Long> findPostIdsWhereViewerConfirmed(@Param("postIds") Collection<Long> postIds,
                                               @Param("userEmail") String userEmail);

    @Transactional
    @Modifying
    @Query("DELETE FROM PostConfirm c WHERE c.postId = :postId " +
           "AND lower(c.userEmail) = lower(:userEmail)")
    int deleteByPostAndUser(@Param("postId") Long postId,
                            @Param("userEmail") String userEmail);

    @Transactional
    @Modifying
    @Query("DELETE FROM PostConfirm c WHERE c.postId = :postId")
    void deleteAllByPostId(@Param("postId") Long postId);
}
