package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.AlertSourceRevision;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** V110 source revisions — see {@link AlertSourceRevision} and {@code InterpretationRecorder}. */
public interface AlertSourceRevisionRepo extends JpaRepository<AlertSourceRevision, String> {

    /**
     * Moves {@code lastSeenAt} forward for revisions their source re-listed on
     * a successful poll — one statement for the whole batch. Never moves it
     * back: a row already seen later keeps its time.
     */
    @Transactional
    @Modifying
    @Query("""
           UPDATE AlertSourceRevision r SET r.lastSeenAt = :at
            WHERE r.revisionRef IN :refs
              AND r.lastSeenAt < :at
           """)
    int touchLastSeen(@Param("refs") Collection<String> refs, @Param("at") Instant at);

    /**
     * Retention candidates (contract §6, step 3): not re-listed since
     * {@code cutoff} AND no interpretation snapshot points at them. Oldest
     * first, a page at a time for the drain loop.
     */
    @Query("""
           SELECT r.revisionRef FROM AlertSourceRevision r
            WHERE r.lastSeenAt < :cutoff
              AND NOT EXISTS (SELECT 1 FROM AlertInterpretationSnapshot s
                               WHERE s.revisionRef = r.revisionRef)
            ORDER BY r.lastSeenAt ASC
           """)
    List<String> findSweepableRefs(@Param("cutoff") Instant cutoff, Pageable page);

    /**
     * Deletes the candidates, re-checking the reference inside the DELETE so a
     * snapshot written between the SELECT and here keeps its revision. (The
     * FK would refuse the delete anyway; this keeps the batch from failing.)
     */
    @Transactional
    @Modifying
    @Query("""
           DELETE FROM AlertSourceRevision r
            WHERE r.revisionRef IN :refs
              AND r.lastSeenAt < :cutoff
              AND NOT EXISTS (SELECT 1 FROM AlertInterpretationSnapshot s
                               WHERE s.revisionRef = r.revisionRef)
           """)
    int deleteSweepable(@Param("refs") Collection<String> refs, @Param("cutoff") Instant cutoff);
}
