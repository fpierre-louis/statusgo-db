package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.PlanActivationAck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlanActivationAckRepo extends JpaRepository<PlanActivationAck, Long> {

    List<PlanActivationAck> findByActivationIdOrderByAckedAtAsc(String activationId);

    Optional<PlanActivationAck> findByActivationIdAndRecipientEmailIgnoreCase(
            String activationId, String recipientEmail);
}
