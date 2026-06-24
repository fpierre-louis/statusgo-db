package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.VerificationApplicationNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VerificationApplicationNoteRepo extends JpaRepository<VerificationApplicationNote, Long> {
    List<VerificationApplicationNote> findByApplicationIdOrderByCreatedAtAsc(Long applicationId);
}
