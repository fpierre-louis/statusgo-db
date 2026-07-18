package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.CivicCoverageGap;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Civic epic Slice 2 — the zip-keyed orphan coverage-gap ledger. */
public interface CivicCoverageGapRepo extends JpaRepository<CivicCoverageGap, Long> {

    Optional<CivicCoverageGap> findByZip(String zip);
}
