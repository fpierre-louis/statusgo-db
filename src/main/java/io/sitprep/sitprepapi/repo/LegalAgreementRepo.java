package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.LegalAgreement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LegalAgreementRepo extends JpaRepository<LegalAgreement, Long> {
}
