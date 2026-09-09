package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.GroupInviteRedemption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GroupInviteRedemptionRepo extends JpaRepository<GroupInviteRedemption, Long> {
    Optional<GroupInviteRedemption> findByInviteIdAndUserEmail(String inviteId, String userEmail);
}
