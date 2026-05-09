package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.GroupInvite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface GroupInviteRepo extends JpaRepository<GroupInvite, String> {

    /**
     * Live invites for a group — not revoked, not expired, and (if
     * maxUses is set) under the cap. Used by the admin "Manage
     * invites" panel.
     */
    @Query("""
        SELECT i FROM GroupInvite i
        WHERE i.groupId = :groupId
          AND i.revokedAt IS NULL
          AND i.expiresAt > :now
          AND (i.maxUses IS NULL OR i.usedCount < i.maxUses)
        ORDER BY i.issuedAt DESC
        """)
    List<GroupInvite> findActiveByGroup(
            @Param("groupId") String groupId,
            @Param("now") Instant now
    );

    Optional<GroupInvite> findById(String id);
}
