package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.UserInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserInfoRepo extends JpaRepository<UserInfo, String> {
    Optional<UserInfo> findByUserEmail(String email);
    List<UserInfo> findByJoinedGroupIDsContaining(String groupId);
    Optional<UserInfo> findByFcmtoken(String fcmtoken);
}
