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

    /*
     * BOTH "ACTIVE" QUERIES BELOW REQUIRE endedAt IS NULL AS WELL AS AN UNEXPIRED
     * expiresAt, AND THE TWO CONDITIONS ARE NOT THE SAME THING. `expiresAt` is a
     * 72-hour timer; `endedAt` is a household saying it is over. Until the second
     * existed the first was the only way out, so Home read EVACUATING for three
     * days off a row nobody could close.
     *
     * If a third "active" query is ever added here, it carries the same pair.
     * One that checks only expiry would keep an ended activation alive on exactly
     * one surface, which is the disagreement this change exists to end.
     */

    /**
     * The owner's most recent non-expired activation, if any. Owner-scoped
     * consumers use this when the caller already knows the activation owner.
     * Household-wide surfaces should prefer {@link #findActiveByOwnerEmail}
     * across every household member candidate so non-owner launched plans are
     * visible to the same audience that receives activation pushes.
     */
    @Query(
        "SELECT a FROM PlanActivation a " +
        "WHERE LOWER(a.ownerEmail) = LOWER(:email) " +
        "AND a.expiresAt > :now " +
        "AND a.endedAt IS NULL " +
        "ORDER BY a.activatedAt DESC"
    )
    Optional<PlanActivation> findFirstActiveByOwnerEmail(
            @Param("email") String email,
            @Param("now") Instant now
    );

    /**
     * All non-expired activations for an owner. Used to notify open
     * recipient views that activation-visible plan data changed, and by
     * household-wide readers that need to inspect each possible activation
     * owner before choosing the newest visible plan.
     */
    @Query(
        "SELECT a FROM PlanActivation a " +
        "WHERE LOWER(a.ownerEmail) = LOWER(:email) " +
        "AND a.expiresAt > :now " +
        "AND a.endedAt IS NULL " +
        "ORDER BY a.activatedAt DESC"
    )
    List<PlanActivation> findActiveByOwnerEmail(
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
