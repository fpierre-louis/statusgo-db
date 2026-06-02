package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.HouseholdRitual;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HouseholdRitualRepo extends JpaRepository<HouseholdRitual, Long> {

    List<HouseholdRitual> findByHouseholdId(String householdId);

    /**
     * The single-of-kind lookup the FE relies on for "does this
     * household have a weekly check-in opted in?" Round 1 enforces
     * at-most-one ritual per (household, kind); v2 with multi-ritual
     * support will need a list method instead.
     */
    Optional<HouseholdRitual> findFirstByHouseholdIdAndKind(String householdId, String kind);

    /**
     * Cross-household scan for the §4 Round 2 scheduler — pulls every
     * opted-in ritual of a kind so the sweep can decide which are due
     * right now. Beta-scale fine without pagination; revisit if
     * household count climbs.
     */
    List<HouseholdRitual> findByKind(String kind);
}
