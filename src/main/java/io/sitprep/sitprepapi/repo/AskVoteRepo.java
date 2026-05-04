package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.AskVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AskVoteRepo extends JpaRepository<AskVote, Long> {

    Optional<AskVote> findByTargetTypeAndTargetIdAndVoterEmail(
            String targetType, Long targetId, String voterEmail);

    /**
     * Batched "what did this voter vote on?" lookup for hydrating the FE.
     * Returns the raw votes; service layer maps to
     * {@code Map<targetType:targetId, value>}.
     */
    @Query("SELECT v FROM AskVote v " +
           "WHERE v.voterEmail = :voter " +
           "  AND v.targetType = :type " +
           "  AND v.targetId IN :ids")
    List<AskVote> findVoterVotesIn(
            @Param("voter") String voterEmail,
            @Param("type") String targetType,
            @Param("ids") Collection<Long> targetIds);

    void deleteByTargetTypeAndTargetIdAndVoterEmail(
            String targetType, Long targetId, String voterEmail);
}
