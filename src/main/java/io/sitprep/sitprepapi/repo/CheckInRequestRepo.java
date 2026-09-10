package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.CheckInRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface CheckInRequestRepo extends JpaRepository<CheckInRequest, Long> {

    /**
     * Every ask recorded for this group inside this window.
     *
     * <p>The window is matched on {@code >=} rather than equality on purpose: a
     * nudge sent before an alert opened carries its own request time as the
     * window, and it should still count as "this person was asked" for a window
     * that opened afterwards only if the ask came after it. Callers pass the
     * window start they are reasoning about.</p>
     */
    List<CheckInRequest> findByGroupIdAndWindowStartedAtGreaterThanEqual(String groupId, Instant windowStartedAt);

    List<CheckInRequest> findByGroupIdAndSubjectEmailIgnoreCase(String groupId, String subjectEmail);
}
