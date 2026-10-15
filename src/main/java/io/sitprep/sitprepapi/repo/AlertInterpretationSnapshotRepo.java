package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.AlertInterpretationSnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** V110 interpretation snapshots — see {@link AlertInterpretationSnapshot} and {@code InterpretationRecorder}. */
public interface AlertInterpretationSnapshotRepo extends JpaRepository<AlertInterpretationSnapshot, UUID> {

    /** Id and fingerprint only — what the recorder's cache holds. */
    interface IdByFingerprint {
        UUID getId();
        String getFingerprint();
    }

    @Query("SELECT s.id AS id, s.fingerprint AS fingerprint FROM AlertInterpretationSnapshot s WHERE s.fingerprint IN :fps")
    List<IdByFingerprint> findIdsByFingerprintIn(@Param("fps") Collection<String> fps);

    /**
     * Retention candidates (contract §6, step 2): created before {@code cutoff},
     * pointed at by no notification row, and whose revision has not been
     * re-listed since {@code cutoff} either.
     *
     * <p>The last clause is stricter than the contract's two on purpose. A
     * revision the upstream still lists — a FEMA declaration open for months —
     * keeps its snapshot however old, so the live feed and the next dispatch
     * keep pointing at the same row. Without it a still-current snapshot would
     * be deleted on day 31 and re-created under a new id on the next tick, and
     * a notification written in between could name the deleted one.</p>
     */
    @Query("""
           SELECT s.id FROM AlertInterpretationSnapshot s
            WHERE s.createdAt < :cutoff
              AND NOT EXISTS (SELECT 1 FROM NotificationLog n
                               WHERE n.interpretationSnapshotId = s.id)
              AND NOT EXISTS (SELECT 1 FROM AlertSourceRevision r
                               WHERE r.revisionRef = s.revisionRef
                                 AND r.lastSeenAt >= :cutoff)
            ORDER BY s.createdAt ASC
           """)
    List<UUID> findSweepableIds(@Param("cutoff") Instant cutoff, Pageable page);

    /** Deletes the candidates, re-checking the notification reference inside the DELETE. */
    @Transactional
    @Modifying
    @Query("""
           DELETE FROM AlertInterpretationSnapshot s
            WHERE s.id IN :ids
              AND NOT EXISTS (SELECT 1 FROM NotificationLog n
                               WHERE n.interpretationSnapshotId = s.id)
           """)
    int deleteSweepable(@Param("ids") Collection<UUID> ids);
}
