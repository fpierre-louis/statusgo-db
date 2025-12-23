package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.RSEventAttendance;
import io.sitprep.sitprepapi.domain.RSAttendanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RSEventAttendanceRepo extends JpaRepository<RSEventAttendance, String> {

    Optional<RSEventAttendance> findByEventIdAndAttendeeEmailIgnoreCase(String eventId, String attendeeEmail);

    long countByEventIdAndStatusIn(String eventId, Collection<RSAttendanceStatus> statuses);

    @Query("""
        SELECT a
        FROM RSEventAttendance a
        WHERE lower(a.attendeeEmail) = lower(:email)
          AND a.eventId IN :eventIds
    """)
    List<RSEventAttendance> findForViewerAcrossEvents(@Param("email") String email,
                                                      @Param("eventIds") Collection<String> eventIds);

    // -------------------------
    // Bulk projections used by feed enrichment
    // -------------------------

    interface EventCountRow {
        String getEventId();
        Long getCnt();
    }

    @Query("""
        SELECT a.eventId as eventId, COUNT(a) as cnt
        FROM RSEventAttendance a
        WHERE a.eventId IN :eventIds
          AND a.status IN :statuses
        GROUP BY a.eventId
    """)
    List<EventCountRow> countAcrossEvents(@Param("eventIds") Collection<String> eventIds,
                                          @Param("statuses") Collection<RSAttendanceStatus> statuses);

    interface EventAttendeeEmailRow {
        String getEventId();
        String getAttendeeEmail();
    }

    @Query("""
        SELECT a.eventId as eventId, a.attendeeEmail as attendeeEmail
        FROM RSEventAttendance a
        WHERE a.eventId IN :eventIds
          AND a.status IN :statuses
        ORDER BY a.eventId asc
    """)
    List<EventAttendeeEmailRow> findAttendeeEmailsAcrossEvents(@Param("eventIds") Collection<String> eventIds,
                                                               @Param("statuses") Collection<RSAttendanceStatus> statuses);
}