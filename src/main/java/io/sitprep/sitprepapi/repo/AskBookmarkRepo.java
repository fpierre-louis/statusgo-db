package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.AskBookmark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AskBookmarkRepo extends JpaRepository<AskBookmark, Long> {

    Optional<AskBookmark> findByUserEmailAndTargetTypeAndTargetKey(
            String userEmail, String targetType, String targetKey);

    /**
     * Every bookmark on one target, across all users. targetKey is a STRING
     * because guides are slugs — numeric ids are stored stringified.
     */
    @Modifying
    @Query("DELETE FROM AskBookmark b WHERE b.targetType = :tt AND b.targetKey = :key")
    int deleteAllForTarget(@Param("tt") String targetType, @Param("key") String targetKey);

    @Modifying
    @Query("DELETE FROM AskBookmark b WHERE b.targetType = :tt AND b.targetKey IN :keys")
    int deleteAllForTargets(@Param("tt") String targetType,
                            @Param("keys") Collection<String> targetKeys);

    void deleteByUserEmailAndTargetTypeAndTargetKey(
            String userEmail, String targetType, String targetKey);

    List<AskBookmark> findByUserEmailOrderByCreatedAtDesc(String userEmail);

    /** Hydration helper: which of these target keys does this user already have bookmarked? */
    @Query("SELECT b FROM AskBookmark b " +
           "WHERE b.userEmail = :user " +
           "  AND b.targetType = :type " +
           "  AND b.targetKey IN :keys")
    List<AskBookmark> findUserBookmarksIn(
            @Param("user") String userEmail,
            @Param("type") String targetType,
            @Param("keys") Collection<String> targetKeys);
}
