package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.AgencyStaff;
import io.sitprep.sitprepapi.repo.AgencyStaffRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
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

    /**
     * Server-side shape check for a staff email. A staff row is a PRIVILEGE
     * GRANT — it widens the civic-queue read gate
     * ({@code AgencyAuthorizationService.requireAgencyStaffOrAdmin}) — and the
     * column deliberately has NO FK to {@code user_info} (an agency may add crew
     * before they install). So a typo'd address creates a durable grant for a
     * mailbox nobody controls, inherited by whoever later registers it. This is
     * the authoritative guard; the frontend's matching check is fast feedback
     * only and is not a security boundary.
     *
     * <p>Deliberately permissive on the local part (RFC 5321 allows far more
     * than most validators admit) — it rejects the realistic typo classes
     * (missing {@code @}, missing dot/TLD, embedded whitespace), not exotic but
     * legal addresses.</p>
     */
    private static final Pattern EMAIL_SHAPE = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

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
        if (!EMAIL_SHAPE.matcher(e).matches()) {
            throw new IllegalArgumentException("Staff email is not a valid email address");
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
