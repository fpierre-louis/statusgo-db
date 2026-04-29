package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.PlanActivation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
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

    /**
     * IDs of activations whose {@code expiresAt} is older than the cutoff,
     * paginated. Used by {@code ActivationExpirySweepService} to bound each
     * scheduled tick — a single backlog burst (e.g. after a long pause in
     * scheduler runs) can't lock the table or balloon memory. The cutoff
     * is typically {@code now - retentionAfterExpiry} so recipients still
     * have a grace window to view their stale link before it's purged.
     */
    @Query(
        "SELECT a.id FROM PlanActivation a " +
        "WHERE a.expiresAt < :cutoff " +
        "ORDER BY a.expiresAt ASC"
    )
    List<String> findIdsExpiredBefore(@Param("cutoff") Instant cutoff, Pageable page);
}
