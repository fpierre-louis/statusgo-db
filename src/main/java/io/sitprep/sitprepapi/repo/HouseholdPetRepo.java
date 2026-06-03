package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.HouseholdPet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HouseholdPetRepo extends JpaRepository<HouseholdPet, String> {

    List<HouseholdPet> findByHouseholdIdOrderByCreatedAtAsc(String householdId);
}
