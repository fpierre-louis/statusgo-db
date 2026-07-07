package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.DmThread;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DmThreadRepo extends JpaRepository<DmThread, Long> {

    /** Pair lookup — callers pass the pair pre-sorted (a < b, lowercase). */
    Optional<DmThread> findByParticipantAEmailAndParticipantBEmail(String a, String b);

    /** Viewer's inbox, newest conversation first (unmessaged threads last). */
    @Query("""
            SELECT t FROM DmThread t
            WHERE t.participantAEmail = :email OR t.participantBEmail = :email
            ORDER BY t.lastMessageAt DESC NULLS LAST
            """)
    List<DmThread> findInbox(@Param("email") String email);
}
