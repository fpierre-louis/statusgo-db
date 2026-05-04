package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.AskTip;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AskTipRepo extends JpaRepository<AskTip, Long> {

    List<AskTip> findAllByOrderByIdDesc(Pageable pageable);

    List<AskTip> findByIdLessThanOrderByIdDesc(Long beforeId, Pageable pageable);

    List<AskTip> findByZipBucketOrderByIdDesc(String zipBucket, Pageable pageable);

    List<AskTip> findByZipBucketAndIdLessThanOrderByIdDesc(
            String zipBucket, Long beforeId, Pageable pageable);

    @Query("SELECT t FROM AskTip t " +
           "WHERE LOWER(t.title) LIKE :q " +
           "   OR LOWER(t.body) LIKE :q " +
           "ORDER BY t.voteScore DESC, t.id DESC")
    List<AskTip> searchByTokens(@Param("q") String q, Pageable pageable);

    @Query("SELECT t FROM AskTip t " +
           "WHERE t.createdAt >= :since " +
           "ORDER BY t.voteScore DESC, t.id DESC")
    List<AskTip> topSince(@Param("since") Instant since, Pageable pageable);

    @Modifying
    @Query("UPDATE AskTip t SET t.voteScore = t.voteScore + :delta WHERE t.id = :id")
    int bumpVoteScore(@Param("id") Long id, @Param("delta") int delta);

    @Modifying
    @Query("UPDATE AskTip t SET t.viewCount = t.viewCount + 1 WHERE t.id = :id")
    int incrementViewCount(@Param("id") Long id);
}
