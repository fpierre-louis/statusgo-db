package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.HouseholdInviteRequest;
import io.sitprep.sitprepapi.domain.HouseholdInviteRequest.Status;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HouseholdInviteRequestRepo extends JpaRepository<HouseholdInviteRequest, String> {

    /**
     * Pending invite requests for a household, newest first. Powers the
     * admin-side "Needs your attention" row + the InviteApprovalSheet
     * default list.
     */
    List<HouseholdInviteRequest> findByHouseholdIdAndStatusOrderByCreatedAtDesc(
            String householdId, Status status);

    /**
     * Dup-suppression check before writing a new row. A second pending
     * request for the same (household, candidate) is a no-op — the FE
     * still shows "request sent" but we don't double-push admins.
     */
    Optional<HouseholdInviteRequest> findFirstByHouseholdIdAndCandidateEmailIgnoreCaseAndStatus(
            String householdId, String candidateEmail, Status status);
}
