package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.AlertModeState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AlertModeStateRepo extends JpaRepository<AlertModeState, String> {

    /**
     * Cells currently above {@code calm}. Useful for the eventual
     * admin dashboard ("which areas are in alert mode right now") and
     * for cleanup ticks that want to skip dormant cells.
     */
    @Query("SELECT s FROM AlertModeState s WHERE s.state <> 'calm'")
    List<AlertModeState> findActiveStates();
}
