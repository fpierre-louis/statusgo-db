package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.UserInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserInfoRepo extends JpaRepository<UserInfo, String> {

    // existing
    Optional<UserInfo> findByUserEmail(String email);

    // ✅ add this so calls to findByUserEmailIgnoreCase(...) compile
    Optional<UserInfo> findByUserEmailIgnoreCase(String email);

    // nice-to-have: a normalized helper you can call anywhere
    default Optional<UserInfo> findByUserEmailNormalized(String email) {
        return findByUserEmail(email == null ? null : email.toLowerCase());
    }

    List<UserInfo> findByUserEmailIn(List<String> emails);

    // ❗ joinedGroupIDs is Set<String> in UserInfo, so this param must be String
    @Query("SELECT u.userEmail FROM UserInfo u JOIN u.joinedGroupIDs g WHERE g = :groupId")
    List<String> findEmailsByGroupId(@Param("groupId") String groupId);
}
