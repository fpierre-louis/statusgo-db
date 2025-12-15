package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.RSGroupMember;
import io.sitprep.sitprepapi.domain.RSMemberStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RSGroupMemberRepo extends JpaRepository<RSGroupMember, String> {

    // --- Existing helpers (fine to keep) ---
    Optional<RSGroupMember> findByGroupIdAndMemberEmailIgnoreCase(String groupId, String memberEmail);

    List<RSGroupMember> findByGroupIdOrderByCreatedAtAsc(String groupId);

    List<RSGroupMember> findByMemberEmailIgnoreCaseOrderByCreatedAtDesc(String memberEmail);

    @Query("SELECT m.groupId FROM RSGroupMember m WHERE lower(m.memberEmail) = lower(:email) AND m.status = :status")
    List<String> findActiveGroupIdsForEmail(@Param("email") String email, @Param("status") RSMemberStatus status);

    @Query("SELECT m.memberEmail FROM RSGroupMember m WHERE m.groupId = :groupId AND m.status = :status")
    List<String> findActiveEmailsForGroup(@Param("groupId") String groupId, @Param("status") RSMemberStatus status);

    // --- NEW: fetch userInfo in the same query (prevents lazy-load JSON issues) ---
    @Query("""
        SELECT m
        FROM RSGroupMember m
        LEFT JOIN FETCH m.userInfo u
        WHERE m.groupId = :groupId
        ORDER BY m.createdAt ASC
    """)
    List<RSGroupMember> findByGroupIdWithUserInfo(@Param("groupId") String groupId);

    @Query("""
        SELECT m
        FROM RSGroupMember m
        LEFT JOIN FETCH m.userInfo u
        WHERE lower(m.memberEmail) = lower(:email)
        ORDER BY m.createdAt DESC
    """)
    List<RSGroupMember> findByMemberEmailWithUserInfo(@Param("email") String email);

    @Query("""
        SELECT m
        FROM RSGroupMember m
        LEFT JOIN FETCH m.userInfo u
        WHERE m.groupId = :groupId
          AND lower(m.memberEmail) = lower(:email)
    """)
    Optional<RSGroupMember> findOneWithUserInfo(@Param("groupId") String groupId, @Param("email") String email);
}