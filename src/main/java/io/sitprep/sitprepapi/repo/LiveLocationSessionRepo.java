package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.LiveLocationSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface LiveLocationSessionRepo extends JpaRepository<LiveLocationSession, String> {

    List<LiveLocationSession> findByUserEmailIgnoreCaseAndStoppedAtIsNullAndExpiresAtAfter(
            String userEmail, Instant now);

    @Query("""
        SELECT DISTINCT s FROM LiveLocationSession s JOIN s.groupIds gid
         WHERE gid = :groupId
           AND s.stoppedAt IS NULL
           AND s.expiresAt > :now
         ORDER BY s.startedAt DESC
        """)
    List<LiveLocationSession> findActiveForGroup(@Param("groupId") String groupId,
                                                 @Param("now") Instant now);
}
