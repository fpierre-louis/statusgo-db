package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.PlatformAdmin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlatformAdminRepo extends JpaRepository<PlatformAdmin, Long> {
    Optional<PlatformAdmin> findByEmailIgnoreCase(String email);
    Optional<PlatformAdmin> findByEmailIgnoreCaseAndActiveTrue(String email);
    List<PlatformAdmin> findAllByActiveTrueOrderByEmailAsc();
    long countByActiveTrueAndRole(io.sitprep.sitprepapi.constant.PlatformRole role);
}
