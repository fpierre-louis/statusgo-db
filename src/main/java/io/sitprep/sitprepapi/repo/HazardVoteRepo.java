package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.HazardVote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface HazardVoteRepo extends JpaRepository<HazardVote, Long> {

    Optional<HazardVote> findByTaskIdAndUserEmail(Long taskId, String userEmail);

    List<HazardVote> findByTaskIdIn(Collection<Long> taskIds);
}
