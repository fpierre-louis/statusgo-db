package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.AgencyStaff;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Agency STAFF membership — the sole authority for "is this person staff of this
 * agency". See docs/lanes/AGENCY_STAFF_PHASE0_DESIGN.md.
 *
 * <p>Email is stored lower-cased by {@code AgencyStaffService}; the
 * {@code IgnoreCase} finders are belt-and-suspenders for callers that pass a raw
 * email. The {@code findByUserEmailIgnoreCase} finder is the {@code /api/me}
 * batch arm — "which agencies is this viewer staff of" — served by
 * {@code idx_agency_staff_user}.</p>
 */
public interface AgencyStaffRepo extends JpaRepository<AgencyStaff, Long> {

    /** The staff roster of one agency (list-staff endpoint), oldest first. */
    List<AgencyStaff> findByGroupIdOrderByAddedAtAsc(String groupId);

    /** All agencies this person is staff of — the /api/me fourth-source arm. */
    List<AgencyStaff> findByUserEmailIgnoreCase(String userEmail);

    /** A specific person's staff row on an agency (remove / dedup). */
    Optional<AgencyStaff> findByGroupIdAndUserEmailIgnoreCase(String groupId, String userEmail);

    /** Eligibility check: is this caller staff of this agency? */
    boolean existsByGroupIdAndUserEmailIgnoreCase(String groupId, String userEmail);

    /** Remove a staff row (idempotent delete by the natural pair). */
    long deleteByGroupIdAndUserEmailIgnoreCase(String groupId, String userEmail);
}
