package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.UserInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserInfoRepo extends JpaRepository<UserInfo, String> {

    Optional<UserInfo> findByUserEmail(String email);

    Optional<UserInfo> findByUserEmailIgnoreCase(String email);

    // ✅ NEW: stable identity lookup
    Optional<UserInfo> findByFirebaseUid(String firebaseUid);

    default Optional<UserInfo> findByUserEmailNormalized(String email) {
        return findByUserEmail(email == null ? null : email.toLowerCase());
    }

    List<UserInfo> findByUserEmailIn(List<String> emails);

    @Query("SELECT u.userEmail FROM UserInfo u JOIN u.joinedGroupIDs g WHERE g = :groupId")
    List<String> findEmailsByGroupId(@Param("groupId") String groupId);

    /**
     * All currently-verified publishers (city / county / state / newsroom
     * / utility / Red Cross). Caller refines with Haversine on
     * (latitude, longitude) for the radius filter — same pattern as
     * TaskService.discoverCommunity. Cap-50 trim happens at the service
     * layer.
     */
    List<UserInfo> findByVerifiedPublisherTrue();
}