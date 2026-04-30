package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.Follow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Follow-edge persistence. All queries assume emails are already
 * lowercased — the service layer normalizes before hitting the repo
 * so the unique constraint on (follower, followed) stays
 * case-insensitive without a database expression index.
 */
@Repository
public interface FollowRepo extends JpaRepository<Follow, Long> {

    Optional<Follow> findByFollowerEmailAndFollowedEmail(
            String followerEmail, String followedEmail);

    boolean existsByFollowerEmailAndFollowedEmail(
            String followerEmail, String followedEmail);

    /** Everyone {@code email} follows. Drives feed merge in step 4. */
    List<Follow> findByFollowerEmail(String followerEmail);

    /** Everyone who follows {@code email}. Drives the followers list in step 6. */
    List<Follow> findByFollowedEmail(String followedEmail);

    long countByFollowerEmail(String followerEmail);

    long countByFollowedEmail(String followedEmail);
}
