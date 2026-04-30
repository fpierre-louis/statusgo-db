package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.AlertPost;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AlertPostRepo extends JpaRepository<AlertPost, Long> {

    /**
     * Dedup lookup. Used by {@code AlertDispatchService} before creating
     * a new AlertPost — if a non-resolved row already exists for this
     * (alertId, geocellId), skip. The unique index enforces this at the
     * DB level too; this method makes the application-side flow cleaner.
     */
    Optional<AlertPost> findByAlertIdAndGeocellId(String alertId, String geocellId);

    /**
     * Active (unresolved) AlertPosts for one alertId. Resolve tick
     * iterates this when the upstream alert ends, marking each row's
     * {@code resolvedAt} + cascading visual demotion to the parent
     * {@link io.sitprep.sitprepapi.domain.Post} via PostRepo.
     */
    @Query("""
           SELECT ap FROM AlertPost ap
            WHERE ap.alertId = :alertId
              AND ap.resolvedAt IS NULL
           """)
    List<AlertPost> findActiveByAlertId(@Param("alertId") String alertId);

    /**
     * Distinct alert IDs the dispatcher has already posted for, for
     * any geocell. Used by the dispatch tick as the first-pass filter
     * — anything in this set is either being resolved or has at least
     * one geocell covered. Geocell-level dedup happens via
     * {@link #findByAlertIdAndGeocellId}.
     */
    @Query("SELECT DISTINCT ap.alertId FROM AlertPost ap WHERE ap.resolvedAt IS NULL")
    List<String> findActiveAlertIds();
}
