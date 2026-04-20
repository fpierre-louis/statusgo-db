package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.Group;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GroupRepo extends JpaRepository<Group, String> {

    @Query("SELECT g FROM Group g JOIN g.adminEmails a WHERE LOWER(a) = LOWER(:email)")
    List<Group> findByAdminEmail(@Param("email") String email);

    // Authoritative lookup for "groups this user belongs to" — reads the
    // Group side (member_emails) directly rather than relying on the
    // denormalized UserInfo.joinedGroupIDs cache. Case-insensitive.
    @Query("SELECT g FROM Group g JOIN g.memberEmails m WHERE LOWER(m) = LOWER(:email)")
    List<Group> findByMemberEmail(@Param("email") String email);

    // ✅ For UUID-based lookup by public groupId
    Optional<Group> findByGroupId(String groupId);

    // ✅ Optional: For findByAdminEmailsContaining (alternative to @Query)
    List<Group> findByAdminEmailsContaining(String adminEmail);

    @Query("SELECT g FROM Group g LEFT JOIN FETCH g.memberEmails WHERE g.groupId = :groupId")
    Optional<Group> findByGroupIdWithMembers(@Param("groupId") String groupId);
}
