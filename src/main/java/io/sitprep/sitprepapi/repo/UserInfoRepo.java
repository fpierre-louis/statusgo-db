package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.UserInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserInfoRepo extends JpaRepository<UserInfo, String> {

    // ✅ Primary email query (case-insensitive)
    Optional<UserInfo> findByUserEmailIgnoreCase(String email);

    // ✅ Batch query for multiple users by email
    List<UserInfo> findByUserEmailIn(List<String> emails);

    // ✅ Custom query for members of a group
    @Query("SELECT u.userEmail FROM UserInfo u JOIN u.joinedGroupIDs g WHERE g = :groupId")
    List<String> findEmailsByGroupId(@Param("groupId") Long groupId);
}
