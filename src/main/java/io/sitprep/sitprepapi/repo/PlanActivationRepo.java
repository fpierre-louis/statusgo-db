package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.PlanActivation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface PlanActivationRepo extends JpaRepository<PlanActivation, String> {

    /**
     * The owner's most recent non-expired activation, if any. Used by
     * {@code MeService} to populate {@code MeDto.activeActivationId} so the
     * frontend can flip {@code /home} into Active Dashboard mode without a
     * separate round trip. Returns empty when no activation exists or the
     * latest one has already expired.
     */
    @Query(
        "SELECT a FROM PlanActivation a " +
        "WHERE LOWER(a.ownerEmail) = LOWER(:email) " +
        "AND a.expiresAt > :now " +
        "ORDER BY a.activatedAt DESC"
    )
    Optional<PlanActivation> findFirstActiveByOwnerEmail(
            @Param("email") String email,
            @Param("now") Instant now
    );
}
