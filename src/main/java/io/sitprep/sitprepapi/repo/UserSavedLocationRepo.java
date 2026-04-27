package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.UserSavedLocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserSavedLocationRepo extends JpaRepository<UserSavedLocation, Long> {

    /** All saved places for a user, in stable display order. */
    List<UserSavedLocation> findByOwnerEmailIgnoreCaseOrderByIsHomeDescNameAsc(String ownerEmail);

    /** The user's home, if they've designated one. */
    Optional<UserSavedLocation> findFirstByOwnerEmailIgnoreCaseAndIsHomeTrue(String ownerEmail);
}
