package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.AskQuestion;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * Repository for {@link AskQuestion}. The two listing finders cover the
 * common cases (most-recent N + cursor pagination); search across
 * title+body uses {@link #searchByTokens} which is good enough for v1
 * without a Postgres tsvector index — when usage grows we swap the LIKE
 * to {@code @@ to_tsquery(...)} without changing the call site.
 */
public interface AskQuestionRepo extends JpaRepository<AskQuestion, Long> {

    /** Most-recent N, descending by id. Initial-page loader. */
    List<AskQuestion> findAllByOrderByIdDesc(Pageable pageable);

    /** Older page after a cursor id. */
    List<AskQuestion> findByIdLessThanOrderByIdDesc(Long beforeId, Pageable pageable);

    /** Same, scoped to one zip bucket (local feed). */
    List<AskQuestion> findByZipBucketOrderByIdDesc(String zipBucket, Pageable pageable);

    List<AskQuestion> findByZipBucketAndIdLessThanOrderByIdDesc(
            String zipBucket, Long beforeId, Pageable pageable);

    /**
     * Naive token search — case-insensitive substring across title + body.
     * Caller wraps the user query as {@code "%" + token + "%"} (lowercased).
     * Returns at most {@code Pageable.size}; service layer applies the
     * hot-score sort on the slice.
     */
    @Query("SELECT q FROM AskQuestion q " +
           "WHERE LOWER(q.title) LIKE :q " +
           "   OR LOWER(q.body) LIKE :q " +
           "ORDER BY q.voteScore DESC, q.id DESC")
    List<AskQuestion> searchByTokens(@Param("q") String q, Pageable pageable);

    /**
     * Top questions across a time window — used by the "Top questions"
     * strip on /ask. Sorted by vote score with id as tiebreaker. Time-
     * window filter is on {@code createdAt}.
     */
    @Query("SELECT q FROM AskQuestion q " +
           "WHERE q.createdAt >= :since " +
           "ORDER BY q.voteScore DESC, q.id DESC")
    List<AskQuestion> topSince(@Param("since") Instant since, Pageable pageable);

    /** Atomic vote-score bump used in the same transaction as vote insert/delete. */
    @Modifying
    @Query("UPDATE AskQuestion q SET q.voteScore = q.voteScore + :delta WHERE q.id = :id")
    int bumpVoteScore(@Param("id") Long id, @Param("delta") int delta);

    @Modifying
    @Query("UPDATE AskQuestion q SET q.viewCount = q.viewCount + 1 WHERE q.id = :id")
    int incrementViewCount(@Param("id") Long id);

    @Modifying
    @Query("UPDATE AskQuestion q SET q.answerCount = q.answerCount + :delta WHERE q.id = :id")
    int bumpAnswerCount(@Param("id") Long id, @Param("delta") int delta);
}
