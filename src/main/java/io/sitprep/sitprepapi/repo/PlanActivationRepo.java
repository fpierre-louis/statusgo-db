package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.PlanActivation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface PlanActivationRepo extends JpaRepository<PlanActivation, String> {

    /*
     * ── THE LIVE PREDICATE IS WRITTEN ONCE, AND THIS IS THE ONCE ─────────────
     *
     * "Still running" is TWO conditions and they are not the same thing:
     * `expiresAt` is a 72-hour timer, `endedAt` is a household saying it is
     * over. Until the second existed the first was the only way out, so Home
     * read EVACUATING for three days off a row nobody could close.
     *
     * This used to be a CONVENTION — the pair was written out in two identical
     * queries, with a comment asking the next person to remember it in a third.
     * A comment is not a constraint (B8). There is now exactly one query that
     * asks whether an activation is live, so a reader that checks only expiry
     * has nothing to copy from and nowhere to drift.
     *
     * `PlanActivationRepoContractTest` fails the build if a query added here
     * asks `expiresAt >` without also asking `endedAt IS NULL`.
     */

    /**
     * All live activations for an owner — not expired, and not ended by anyone.
     *
     * <p>Newest first. Callers that want only the newest take the first element
     * rather than asking for a second query: {@code findFirstActiveByOwnerEmail}
     * existed for that and was deleted, because with {@code @Query} the
     * {@code findFirst} prefix applies NO limit — Spring Data only derives a
     * limit from a method name when it derives the whole query. Declared
     * {@code Optional}, it therefore threw
     * {@code IncorrectResultSizeDataAccessException: Query did not return a
     * unique result: 2 results were returned} for any owner with two live rows,
     * which {@code createActivation} produces freely (it does not close the
     * previous one). It had no callers, so it never fired.</p>
     *
     * <p>Household-wide surfaces run this across EVERY member candidate rather
     * than the owner alone: an activation is keyed on the launcher's email, so
     * a plan launched by a teenager is invisible to a query that only knows the
     * household's owner.</p>
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
     * Activations whose 72-hour timer has run out and which the expiry sweep
     * has not yet handled.
     *
     * <p>NOT one of the two "active" queries above, and it does not carry their
     * pair — it is their complement. It asks for rows that are over BY THE
     * TIMER and have not been announced, so {@code endedAt IS NULL} appears
     * here for a different reason: a row a person already ended has already
     * broadcast its ending, and announcing it a second time because a clock
     * later agreed would put two endings in the household's history for one
     * event.</p>
     *
     * <p>{@code expiryHandledAt} is what makes the hourly tick idempotent.
     * Ordered oldest-first so a backlog drains in the order it accumulated.</p>
     */
    @Query(
        "SELECT a FROM PlanActivation a " +
        "WHERE a.expiresAt <= :now " +
        "AND a.expiryHandledAt IS NULL " +
        "AND a.endedAt IS NULL " +
        "ORDER BY a.expiresAt ASC"
    )
    List<PlanActivation> findExpiredNotHandled(@Param("now") Instant now, Pageable page);

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
