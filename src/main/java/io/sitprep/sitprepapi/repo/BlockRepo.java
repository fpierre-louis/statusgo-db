package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.Block;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Block-edge persistence. All queries assume emails are pre-lowercased
 * by the service layer so the unique constraint catches case-only
 * duplicates without a database expression index.
 */
@Repository
public interface BlockRepo extends JpaRepository<Block, Long> {

    Optional<Block> findByBlockerEmailAndBlockedEmail(
            String blockerEmail, String blockedEmail);

    boolean existsByBlockerEmailAndBlockedEmail(
            String blockerEmail, String blockedEmail);

    /** Everyone {@code email} has blocked. Drives feed-side suppression. */
    List<Block> findByBlockerEmail(String blockerEmail);

    /** Everyone who has blocked {@code email}. Drives feed-side suppression. */
    List<Block> findByBlockedEmail(String blockedEmail);
}
