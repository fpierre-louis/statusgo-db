package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.GoBag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GoBagRepo extends JpaRepository<GoBag, String> {

    List<GoBag> findByHouseholdIdOrderByCreatedAtAsc(String householdId);

    boolean existsByHouseholdId(String householdId);
}
