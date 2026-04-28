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

@Repository
public interface PostReactionRepo extends JpaRepository<PostReaction, Long> {

    List<PostReaction> findByPostId(Long postId);

    /** Batched roster fetch for a list of posts (chat feed listing). */
    List<PostReaction> findByPostIdIn(Collection<Long> postIds);

    /** Used to detect "already reacted" so add-twice is a no-op. */
    Optional<PostReaction> findByPostIdAndUserEmailIgnoreCaseAndEmoji(
            Long postId, String userEmail, String emoji);

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
