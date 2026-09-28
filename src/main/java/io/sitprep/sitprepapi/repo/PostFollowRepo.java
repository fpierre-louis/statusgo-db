package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.PostFollow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Repository for {@link PostFollow} (V86). Mirrors {@link PostConfirmRepo}. */
@Repository
public interface PostFollowRepo extends JpaRepository<PostFollow, Long> {

    boolean existsByPostIdAndUserEmailIgnoreCase(Long postId, String userEmail);

    /** Everyone following a thread — the reply fan-out reads this. */
    List<PostFollow> findByPostId(Long postId);

    @Transactional
    @Modifying
    @Query("DELETE FROM PostFollow f WHERE f.postId = :postId " +
           "AND lower(f.userEmail) = lower(:userEmail)")
    int deleteByPostAndUser(@Param("postId") Long postId,
                            @Param("userEmail") String userEmail);

    @Transactional
    @Modifying
    @Query("DELETE FROM PostFollow f WHERE f.postId = :postId")
    void deleteAllByPostId(@Param("postId") Long postId);
}
