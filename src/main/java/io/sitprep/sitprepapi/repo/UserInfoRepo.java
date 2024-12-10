package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.UserInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserInfoRepo extends JpaRepository<UserInfo, String> {

    Optional<UserInfo> findByUserEmail(String email);

    List<UserInfo> findByUserEmailIn(List<String> emails);

    @Query("SELECT u.userEmail FROM UserInfo u JOIN u.joinedGroupIDs g WHERE g = :groupId")
    List<String> findEmailsByGroupId(@Param("groupId") Long groupId);

    // Custom Update Query for Updating User Status
    @Modifying
    @Query("UPDATE UserInfo u SET u.userStatus = :userStatus, u.userStatusLastUpdated = CURRENT_TIMESTAMP WHERE u.id = :id")
    int updateUserStatus(@Param("id") String id, @Param("userStatus") String userStatus);

    // Custom Update Query for Updating User Status & Color
    @Modifying
    @Query("UPDATE UserInfo u SET u.userStatus = :userStatus, u.statusColor = :statusColor, u.userStatusLastUpdated = CURRENT_TIMESTAMP WHERE u.id = :id")
    int updateUserStatusAndColor(@Param("id") String id, @Param("userStatus") String userStatus, @Param("statusColor") String statusColor);

    // Native Query (Faster) for Updating User Status & Color
    @Modifying
    @Query(
            value = "UPDATE user_info SET user_status = :userStatus, status_color = :statusColor, user_status_last_updated = CURRENT_TIMESTAMP WHERE user_id = :id",
            nativeQuery = true
    )
    int updateUserStatusAndColorNative(@Param("id") String id, @Param("userStatus") String userStatus, @Param("statusColor") String statusColor);
}
