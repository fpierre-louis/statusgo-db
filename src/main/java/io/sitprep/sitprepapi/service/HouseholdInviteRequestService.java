package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.HouseholdInviteRequest;
import io.sitprep.sitprepapi.domain.HouseholdInviteRequest.Status;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.DtoImages;
import io.sitprep.sitprepapi.dto.HouseholdInviteRequestDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.HouseholdInviteRequestRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Member-initiated invite requests. See {@code HouseholdInviteRequest}
 * entity javadoc + docs/HOME_HOUSEHOLD_MERGE.md §6 for the flow.
 *
 * <p>Auth model: file-request and list-pending are scoped to household
 * members and admins respectively; the resource layer enforces caller
 * identity via {@code AuthUtils.requireAuthenticatedEmail}, and this
 * service validates membership/admin role before doing anything.</p>
 */
@Service
public class HouseholdInviteRequestService {

    private static final Logger log = LoggerFactory.getLogger(HouseholdInviteRequestService.class);

    private final HouseholdInviteRequestRepo repo;
    private final GroupRepo groupRepo;
    private final UserInfoRepo userInfoRepo;
    private final NotificationService notificationService;

    public HouseholdInviteRequestService(HouseholdInviteRequestRepo repo,
                                         GroupRepo groupRepo,
                                         UserInfoRepo userInfoRepo,
                                         NotificationService notificationService) {
        this.repo = repo;
        this.groupRepo = groupRepo;
        this.userInfoRepo = userInfoRepo;
        this.notificationService = notificationService;
    }

    /**
     * Member files a request to add {@code candidateEmail} to the
     * household. Pushes Lane A to every admin (idempotent: a second
     * pending row for the same candidate is a no-op).
     */
    @Transactional
    public HouseholdInviteRequestDto create(String householdId, String requesterEmail, String candidateEmail) {
        if (householdId == null || householdId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Household id required");
        }
        if (candidateEmail == null || candidateEmail.isBlank() || !candidateEmail.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Candidate email required");
        }
        Group household = loadHousehold(householdId);
        String requester = lower(requesterEmail);
        String candidate = lower(candidateEmail);

        // Must be a member of the household to file a request.
        if (!isMember(household, requester)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this household");
        }
        // Can't request someone who's already in.
        if (isMember(household, candidate)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already a member");
        }

        // Dedup: existing pending row → return it instead of creating.
        Optional<HouseholdInviteRequest> existing = repo
                .findFirstByHouseholdIdAndCandidateEmailIgnoreCaseAndStatus(householdId, candidate, Status.PENDING);
        if (existing.isPresent()) {
            return toDto(existing.get());
        }

        HouseholdInviteRequest row = new HouseholdInviteRequest();
        row.setHouseholdId(householdId);
        row.setRequesterEmail(requester);
        row.setCandidateEmail(candidate);
        row.setStatus(Status.PENDING);
        HouseholdInviteRequest saved = repo.save(row);

        // Push every admin. Quiet on errors — push failures don't roll back
        // the row (the admin can still see it in the inbox / on Home).
        notifyAdmins(household, saved);

        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public List<HouseholdInviteRequestDto> listPending(String householdId, String adminEmail) {
        Group household = loadHousehold(householdId);
        requireAdmin(household, adminEmail);
        return repo.findByHouseholdIdAndStatusOrderByCreatedAtDesc(householdId, Status.PENDING)
                .stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Approve a pending request — adds the candidate to the household's
     * memberEmails. Idempotent: a non-PENDING row returns the existing
     * DTO without mutating anything.
     */
    @Transactional
    public HouseholdInviteRequestDto approve(String householdId, String requestId, String adminEmail) {
        HouseholdInviteRequest row = loadRequest(householdId, requestId);
        if (row.getStatus() != Status.PENDING) return toDto(row);
        Group household = loadHousehold(householdId);
        requireAdmin(household, adminEmail);

        // Add to memberEmails (idempotent) + persist Group.
        List<String> emails = household.getMemberEmails() == null ? new ArrayList<>() : new ArrayList<>(household.getMemberEmails());
        String candidate = lower(row.getCandidateEmail());
        boolean already = emails.stream().anyMatch(e -> e != null && e.equalsIgnoreCase(candidate));
        if (!already) {
            emails.add(candidate);
            household.setMemberEmails(emails);
            household.setMemberCount(emails.size());
            household.setUpdatedAt(Instant.now());
            groupRepo.save(household);
        }

        row.setStatus(Status.APPROVED);
        row.setResolvedAt(Instant.now());
        row.setResolverEmail(lower(adminEmail));
        return toDto(repo.save(row));
    }

    @Transactional
    public HouseholdInviteRequestDto decline(String householdId, String requestId, String adminEmail) {
        HouseholdInviteRequest row = loadRequest(householdId, requestId);
        if (row.getStatus() != Status.PENDING) return toDto(row);
        Group household = loadHousehold(householdId);
        requireAdmin(household, adminEmail);
        row.setStatus(Status.DECLINED);
        row.setResolvedAt(Instant.now());
        row.setResolverEmail(lower(adminEmail));
        return toDto(repo.save(row));
    }

    // ── helpers ───────────────────────────────────────────────────────

    private Group loadHousehold(String householdId) {
        return groupRepo.findByGroupId(householdId)
                .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Household not found"));
    }

    private HouseholdInviteRequest loadRequest(String householdId, String requestId) {
        HouseholdInviteRequest row = repo.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invite request not found"));
        if (!householdId.equals(row.getHouseholdId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invite request not found");
        }
        return row;
    }

    private static String lower(String s) { return s == null ? null : s.trim().toLowerCase(); }

    private static boolean isMember(Group household, String email) {
        if (email == null) return false;
        List<String> members = household.getMemberEmails();
        if (members == null) return false;
        for (String m : members) {
            if (m != null && m.equalsIgnoreCase(email)) return true;
        }
        return false;
    }

    private void requireAdmin(Group household, String email) {
        String e = lower(email);
        if (e == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        boolean isOwner = e.equalsIgnoreCase(household.getOwnerEmail());
        boolean isAdmin = household.getAdminEmails() != null
                && household.getAdminEmails().stream().anyMatch(a -> a != null && a.equalsIgnoreCase(e));
        if (!isOwner && !isAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admins only");
        }
    }

    /**
     * Fan a Lane A push to every household admin. {@code deliverPresenceAware}
     * runs through PushPolicyService, so per-recipient quiet-hours / mutes
     * / rate-limits still apply.
     */
    private void notifyAdmins(Group household, HouseholdInviteRequest row) {
        List<String> admins = new ArrayList<>();
        if (household.getOwnerEmail() != null) admins.add(household.getOwnerEmail());
        if (household.getAdminEmails() != null) admins.addAll(household.getAdminEmails());
        if (admins.isEmpty()) return;

        UserInfo requester = userInfoRepo.findByUserEmailIgnoreCase(row.getRequesterEmail()).orElse(null);
        String requesterName = requester != null
                ? trimToNull(joinName(requester.getUserFirstName(), requester.getUserLastName()))
                : null;
        String householdName = household.getGroupName() == null ? "your household" : household.getGroupName();
        String title = (requesterName != null ? requesterName : "Someone")
                + " wants to add a member";
        String body = (requesterName != null ? requesterName : "A household member")
                + " requested to add " + row.getCandidateEmail() + " to " + householdName + ".";
        // Deep-link the admin straight to the approval surface. The FE
        // routes /household/{id}/invite-requests/{requestId} to the
        // InviteApprovalSheet.
        String targetUrl = "/household/" + row.getHouseholdId() + "/invite-requests/" + row.getId();
        String referenceId = row.getId();

        for (String adminEmail : admins.stream().distinct().toList()) {
            if (adminEmail == null || adminEmail.isBlank()) continue;
            if (adminEmail.equalsIgnoreCase(row.getRequesterEmail())) continue; // don't push to yourself
            UserInfo admin = userInfoRepo.findByUserEmailIgnoreCase(adminEmail).orElse(null);
            if (admin == null) continue;
            try {
                notificationService.deliverPresenceAware(
                        adminEmail,
                        title,
                        body,
                        requesterName != null ? requesterName : "SitPrep",
                        null,
                        "pending_member",
                        referenceId,
                        targetUrl,
                        null,
                        admin.getFcmtoken()
                );
            } catch (Exception ex) {
                log.warn("InviteRequest: push to admin {} failed: {}", adminEmail, ex.getMessage());
            }
        }
    }

    private static String joinName(String first, String last) {
        if (first == null && last == null) return null;
        if (first == null) return last;
        if (last == null) return first;
        return (first + " " + last).trim();
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private HouseholdInviteRequestDto toDto(HouseholdInviteRequest row) {
        UserInfo requester = userInfoRepo.findByUserEmailIgnoreCase(row.getRequesterEmail()).orElse(null);
        UserInfo candidate = userInfoRepo.findByUserEmailIgnoreCase(row.getCandidateEmail()).orElse(null);
        return new HouseholdInviteRequestDto(
                row.getId(),
                row.getHouseholdId(),
                row.getStatus().name(),
                row.getRequesterEmail(),
                requester == null ? null : requester.getUserFirstName(),
                requester == null ? null : requester.getUserLastName(),
                requester == null ? null : DtoImages.avatar(requester.getProfileImageUrl()),
                row.getCandidateEmail(),
                candidate == null ? null : candidate.getUserFirstName(),
                candidate == null ? null : candidate.getUserLastName(),
                candidate == null ? null : DtoImages.avatar(candidate.getProfileImageUrl()),
                row.getCreatedAt(),
                row.getResolvedAt(),
                row.getResolverEmail()
        );
    }
}
