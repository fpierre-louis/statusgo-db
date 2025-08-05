package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.UserInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserInfoRepo extends JpaRepository<UserInfo, String> {

    // ✅ Use consistent lowercase-only email matching
    Optional<UserInfo> findByUserEmail(String email);

    // ✅ Batch query for multiple users by email (frontend must ensure lowercase)
    List<UserInfo> findByUserEmailIn(List<String> emails);

    // ✅ Custom query for group membership
    @Query("SELECT u.userEmail FROM UserInfo u JOIN u.joinedGroupIDs g WHERE g = :groupId")
    List<String> findEmailsByGroupId(@Param("groupId") Long groupId);
}
