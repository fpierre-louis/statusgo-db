package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.Group;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GroupRepo extends JpaRepository<Group, Long> {

    @Query("SELECT g FROM Group g JOIN g.adminEmails a WHERE a = ?1")
    List<Group> findByAdminEmail(String adminEmail);
}
