package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.Group;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface GroupRepo extends JpaRepository<Group, Long> {

    @Query("SELECT g FROM Group g JOIN g.adminEmails a WHERE a = ?1")
    List<Group> findByAdminEmail(String adminEmail);
}
