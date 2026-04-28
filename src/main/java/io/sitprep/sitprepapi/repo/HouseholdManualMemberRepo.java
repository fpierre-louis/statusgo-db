package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.HouseholdManualMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HouseholdManualMemberRepo extends JpaRepository<HouseholdManualMember, String> {

    List<HouseholdManualMember> findByHouseholdIdOrderByCreatedAtAsc(String householdId);
}
