package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.VerificationApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VerificationApplicationRepo extends JpaRepository<VerificationApplication, Long> {

    Optional<VerificationApplication> findFirstByGroupIdOrderByUpdatedAtDesc(String groupId);

    Optional<VerificationApplication> findFirstByOfficialEmailIgnoreCaseAndPublicNameIgnoreCaseAndStatusInOrderByUpdatedAtDesc(
            String officialEmail,
            String publicName,
            Collection<VerificationApplication.Status> statuses);

    List<VerificationApplication> findByStatusOrderByUpdatedAtDesc(VerificationApplication.Status status);

    List<VerificationApplication> findAllByOrderByUpdatedAtDesc();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE VerificationApplication a
              SET a.assignedConsultantEmail = :email,
                  a.status = :status,
                  a.updatedAt = :now
           WHERE a.id = :id
              AND a.assignedConsultantEmail IS NULL
              AND a.status IN :claimableStatuses
           """)
    int claimIfUnassigned(@Param("id") Long id,
                          @Param("email") String email,
                          @Param("status") VerificationApplication.Status status,
                          @Param("claimableStatuses") Collection<VerificationApplication.Status> claimableStatuses,
                          @Param("now") Instant now);
}
