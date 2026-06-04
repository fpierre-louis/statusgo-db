package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.VerificationApplication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VerificationApplicationRepo extends JpaRepository<VerificationApplication, Long> {

    Optional<VerificationApplication> findFirstByGroupIdOrderByUpdatedAtDesc(String groupId);

    List<VerificationApplication> findByStatusOrderByUpdatedAtDesc(VerificationApplication.Status status);

    List<VerificationApplication> findAllByOrderByUpdatedAtDesc();
}
