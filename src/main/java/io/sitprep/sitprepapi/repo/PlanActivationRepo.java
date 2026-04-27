package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.PlanActivation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanActivationRepo extends JpaRepository<PlanActivation, String> {
}
