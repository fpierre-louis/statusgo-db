package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.UserInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserInfoRepo extends JpaRepository<UserInfo, String> {

    Optional<UserInfo> findByUserEmail(String email);

    List<UserInfo> findByUserEmailIn(List<String> emails);

    @Query("SELECT u.userEmail FROM UserInfo u JOIN u.joinedGroupIDs g WHERE g = :groupId")
    List<String> findEmailsByGroupId(@Param("groupId") Long groupId);
}
