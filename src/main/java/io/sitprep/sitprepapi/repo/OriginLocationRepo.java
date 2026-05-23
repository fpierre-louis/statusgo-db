package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.OriginLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OriginLocationRepo extends JpaRepository<OriginLocation, Long> {
    List<OriginLocation> findByOwnerEmailIgnoreCase(String ownerEmail);

    /** Rows not yet assigned a household — drained by HouseholdBackfillRunner. */
    List<OriginLocation> findByHouseholdIdIsNull();

    /** All origin locations owned by a household (Phase 2 household-scoped read). */
    List<OriginLocation> findByHouseholdId(String householdId);
}
