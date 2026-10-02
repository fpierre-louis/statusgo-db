package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.UserSavedLocation;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface UserSavedLocationRepo extends JpaRepository<UserSavedLocation, Long> {

    /** All saved places for a user, in stable display order. */
    List<UserSavedLocation> findByOwnerEmailIgnoreCaseOrderByIsHomeDescNameAsc(String ownerEmail);

    /** The user's home, if they've designated one. */
    Optional<UserSavedLocation> findFirstByOwnerEmailIgnoreCaseAndIsHomeTrue(String ownerEmail);

    /** The places a location fix may match for "At &lt;place&gt;" — opt-in ones only (V83). */
    List<UserSavedLocation> findByOwnerEmailIgnoreCaseAndSharePresenceTrue(String ownerEmail);

    /**
     * Coordinates of every saved Home place, for daily-brief cells
     * (DailyBriefs EXEC-3B). Coordinates only: no owner, no name.
     */
    @Query("SELECT l.latitude, l.longitude FROM UserSavedLocation l " +
           "WHERE l.isHome = true AND l.latitude IS NOT NULL AND l.longitude IS NOT NULL")
    List<Object[]> findHomeCoordinates();
}
