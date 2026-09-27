package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.MapConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MapConfirmationRepo extends JpaRepository<MapConfirmation, Long> {

    Optional<MapConfirmation> findByTargetTypeAndTargetIdAndUserEmail(
            String targetType, String targetId, String userEmail);

    /**
     * Per target: distinct people who confirmed since {@code since}, and the
     * latest confirmation. Rows are {@code [targetId, count, lastAt]}; targets
     * with no recent confirmation are absent.
     */
    @Query("""
        SELECT c.targetId, COUNT(DISTINCT c.userEmail), MAX(c.confirmedAt)
          FROM MapConfirmation c
         WHERE c.targetType = :type
           AND c.targetId IN :ids
           AND c.confirmedAt >= :since
         GROUP BY c.targetId
        """)
    List<Object[]> summarize(@Param("type") String targetType,
                             @Param("ids") Collection<String> targetIds,
                             @Param("since") Instant since);
}
