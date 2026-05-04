package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.AskAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AskAnswerRepo extends JpaRepository<AskAnswer, Long> {

    List<AskAnswer> findByQuestionIdOrderByVoteScoreDescIdAsc(Long questionId);

    long countByQuestionId(Long questionId);

    @Query("SELECT a.questionId, COUNT(a) FROM AskAnswer a " +
           "WHERE a.questionId IN :ids GROUP BY a.questionId")
    List<Object[]> countByQuestionIdIn(@Param("ids") Collection<Long> ids);

    @Modifying
    @Query("UPDATE AskAnswer a SET a.voteScore = a.voteScore + :delta WHERE a.id = :id")
    int bumpVoteScore(@Param("id") Long id, @Param("delta") int delta);
}
