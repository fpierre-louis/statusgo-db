package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.LiveActivityToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LiveActivityTokenRepo extends JpaRepository<LiveActivityToken, Long> {

    Optional<LiveActivityToken> findByApnsToken(String apnsToken);

    /** The live per-activity tokens following one reference (a group's situation card). */
    @Query("SELECT t FROM LiveActivityToken t " +
           "WHERE t.kind = :kind AND t.referenceId = :referenceId " +
           "AND t.tokenType = 'update' AND t.endedAt IS NULL " +
           "ORDER BY t.id ASC")
    List<LiveActivityToken> findLiveUpdateTokens(@Param("kind") String kind,
                                                 @Param("referenceId") String referenceId);

    @Query("SELECT COUNT(t) > 0 FROM LiveActivityToken t " +
           "WHERE t.kind = :kind AND t.referenceId = :referenceId " +
           "AND t.tokenType = 'update' AND t.endedAt IS NULL")
    boolean existsLiveUpdateToken(@Param("kind") String kind,
                                  @Param("referenceId") String referenceId);

    /** Live push-to-start tokens of one kind for a set of users (emails are stored lower-cased). */
    @Query("SELECT t FROM LiveActivityToken t " +
           "WHERE t.kind = :kind AND t.tokenType = 'push_to_start' AND t.endedAt IS NULL " +
           "AND t.userEmail IN :emails ORDER BY t.id ASC")
    List<LiveActivityToken> findLivePushToStart(@Param("kind") String kind,
                                                @Param("emails") java.util.Collection<String> emails);

    /** One user's live per-activity tokens of one kind (their request cards). */
    @Query("SELECT t FROM LiveActivityToken t " +
           "WHERE t.kind = :kind AND t.tokenType = 'update' AND t.endedAt IS NULL " +
           "AND t.userEmail = :userEmail ORDER BY t.id ASC")
    List<LiveActivityToken> findLiveUpdateTokensForUser(@Param("kind") String kind,
                                                        @Param("userEmail") String userEmail);

    /** The caller's live rows for one activity (DELETE, and a token rotation). */
    @Query("SELECT t FROM LiveActivityToken t " +
           "WHERE t.userEmail = :userEmail AND t.activityId = :activityId AND t.endedAt IS NULL")
    List<LiveActivityToken> findLiveByUserAndActivity(@Param("userEmail") String userEmail,
                                                      @Param("activityId") String activityId);

    /** Retire every live update token following one reference. */
    @Transactional
    @Modifying
    @Query("UPDATE LiveActivityToken t SET t.endedAt = :now, t.updatedAt = :now " +
           "WHERE t.kind = :kind AND t.referenceId = :referenceId " +
           "AND t.tokenType = 'update' AND t.endedAt IS NULL")
    int endLiveUpdateTokens(@Param("kind") String kind,
                            @Param("referenceId") String referenceId,
                            @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query("UPDATE LiveActivityToken t SET t.endedAt = :now, t.updatedAt = :now " +
           "WHERE t.id IN :ids AND t.endedAt IS NULL")
    int endByIds(@Param("ids") List<Long> ids, @Param("now") Instant now);

    /**
     * Per-activity tokens untouched since {@code cutoff}. Apple ends a Live
     * Activity 8 hours after it starts (12 on the lock screen), so a row older
     * than that follows nothing.
     */
    @Transactional
    @Modifying
    @Query("UPDATE LiveActivityToken t SET t.endedAt = :now " +
           "WHERE t.tokenType = 'update' AND t.endedAt IS NULL AND t.updatedAt < :cutoff")
    int endStaleUpdateTokens(@Param("cutoff") Instant cutoff, @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query("DELETE FROM LiveActivityToken t WHERE t.endedAt IS NOT NULL AND t.endedAt < :cutoff")
    int deleteEndedBefore(@Param("cutoff") Instant cutoff);
}
