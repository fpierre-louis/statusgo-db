package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.EmergencyContact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface EmergencyContactRepo extends JpaRepository<EmergencyContact, Long> {

    /**
     * A manual member was claimed by an account (household roster EXEC-B):
     * every contact that was specifically FOR that person now points at the
     * account. Manual ids are UUIDs, so no household filter is needed.
     */
    @Modifying
    @Query("UPDATE EmergencyContact c SET c.subjectType = 'user', c.subjectId = :email, "
            + "c.subjectName = COALESCE(:name, c.subjectName) "
            + "WHERE c.subjectType = 'manual' AND c.subjectId = :manualId")
    int reassignManualSubject(@Param("manualId") String manualId,
                              @Param("email") String email,
                              @Param("name") String name);
}
