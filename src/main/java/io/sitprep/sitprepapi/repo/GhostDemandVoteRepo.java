package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.GhostDemandVote;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GhostDemandVoteRepo extends JpaRepository<GhostDemandVote, Long> {

    /** Has this resident already registered demand for this ghost group? (idempotency guard) */
    boolean existsByGroupIdAndVoterEmailIgnoreCase(String groupId, String voterEmail);
}
