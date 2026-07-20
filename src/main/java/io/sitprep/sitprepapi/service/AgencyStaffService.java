package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.AgencyStaff;
import io.sitprep.sitprepapi.repo.AgencyStaffRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The single writer/reader of agency STAFF membership
 * (docs/lanes/AGENCY_STAFF_PHASE0_DESIGN.md). Normalizes email (lower-case +
 * trim) so the {@code uk_agency_staff_user_group} unique pair is effectively
 * case-insensitive, and add is idempotent.
 */
@Service
public class AgencyStaffService {

    private final AgencyStaffRepo staffRepo;

    public AgencyStaffService(AgencyStaffRepo staffRepo) {
        this.staffRepo = staffRepo;
    }

    private static String norm(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    /** Add a staff member (idempotent — returns the existing row if already staff). */
    @Transactional
    public AgencyStaff add(String groupId, String email, String addedBy) {
        String e = norm(email);
        if (e.isEmpty()) {
            throw new IllegalArgumentException("Staff email required");
        }
        return staffRepo.findByGroupIdAndUserEmailIgnoreCase(groupId, e)
                .orElseGet(() -> {
                    AgencyStaff s = new AgencyStaff();
                    s.setGroupId(groupId);
                    s.setUserEmail(e);
                    s.setAddedBy(norm(addedBy));
                    return staffRepo.save(s);
                });
    }

    /** Remove a staff member. Idempotent — no-op when not present. */
    @Transactional
    public void remove(String groupId, String email) {
        staffRepo.deleteByGroupIdAndUserEmailIgnoreCase(groupId, norm(email));
    }

    /** This agency's staff roster, oldest first. */
    @Transactional(readOnly = true)
    public List<AgencyStaff> list(String groupId) {
        return staffRepo.findByGroupIdOrderByAddedAtAsc(groupId);
    }

    /** Eligibility check — is this person staff of this agency? */
    @Transactional(readOnly = true)
    public boolean isStaff(String email, String groupId) {
        return staffRepo.existsByGroupIdAndUserEmailIgnoreCase(groupId, norm(email));
    }

    /**
     * The set of group ids this person is staff of — the {@code /api/me}
     * fourth-source arm (one indexed query, folded into every GroupSummary's
     * {@code agencyStaff} flag and used to surface staff-only agencies).
     */
    @Transactional(readOnly = true)
    public Set<String> staffGroupIdsFor(String email) {
        String e = norm(email);
        if (e.isEmpty()) return Set.of();
        return staffRepo.findByUserEmailIgnoreCase(e).stream()
                .map(AgencyStaff::getGroupId)
                .collect(Collectors.toSet());
    }
}
