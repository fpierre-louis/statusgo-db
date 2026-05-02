package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.GroupPostReaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface GroupPostReactionRepo extends JpaRepository<GroupPostReaction, Long> {

    List<GroupPostReaction> findByPostId(Long postId);

    /** Batched roster fetch for a list of posts (chat feed listing). */
    List<GroupPostReaction> findByPostIdIn(Collection<Long> postIds);

    /** Used to detect "already reacted" so add-twice is a no-op. */
    Optional<GroupPostReaction> findByPostIdAndUserEmailIgnoreCaseAndEmoji(
            Long postId, String userEmail, String emoji);

    @Modifying
    @Query("DELETE FROM GroupPostReaction r WHERE r.postId = :postId " +
           "AND lower(r.userEmail) = lower(:userEmail) AND r.emoji = :emoji")
    int deleteByPostUserEmoji(@Param("postId") Long postId,
                              @Param("userEmail") String userEmail,
                              @Param("emoji") String emoji);

    @Modifying
    @Query("DELETE FROM GroupPostReaction r WHERE r.postId = :postId")
    void deleteAllByPostId(@Param("postId") Long postId);
}
