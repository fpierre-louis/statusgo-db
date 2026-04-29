package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.PlanActivationAck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlanActivationAckRepo extends JpaRepository<PlanActivationAck, Long> {

    List<PlanActivationAck> findByActivationIdOrderByAckedAtAsc(String activationId);

    Optional<PlanActivationAck> findByActivationIdAndRecipientEmailIgnoreCase(
            String activationId, String recipientEmail);

    /**
     * Bulk delete acks tied to the given activations. Used by
     * {@code ActivationExpirySweepService} ahead of deleting the parent
     * activation rows (no DB-level FK cascade is declared, so cascade is
     * the application's responsibility). Returns the number of rows
     * deleted for log/telemetry.
     */
    @Modifying
    @Query("DELETE FROM PlanActivationAck a WHERE a.activationId IN :ids")
    int deleteByActivationIdIn(@Param("ids") Collection<String> ids);
}
