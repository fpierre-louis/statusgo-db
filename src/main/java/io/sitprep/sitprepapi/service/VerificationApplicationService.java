package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.GroupRole;
import io.sitprep.sitprepapi.domain.AdminAuditLog;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.VerificationApplication;
import io.sitprep.sitprepapi.dto.AgencyPipelineSummaryDto;
import io.sitprep.sitprepapi.dto.ReviewVerificationApplicationRequest;
import io.sitprep.sitprepapi.dto.SubmitVerificationApplicationRequest;
import io.sitprep.sitprepapi.dto.VerificationApplicationDto;
import io.sitprep.sitprepapi.repo.AdminAuditLogRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.VerificationApplicationRepo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class VerificationApplicationService {

    private static final Set<String> ACCOUNT_TYPES = Set.of(
            "business",
            "organization",
            "city",
            "county",
            "state",
            "utility",
            "public-safety",
            "official-alerting-authority",
            "other"
    );

    private final VerificationApplicationRepo applicationRepo;
    private final GroupRepo groupRepo;
    private final VerifiedPublisherService verifiedPublisherService;
    private final AgencyAuthorizationService agencyAuthorizationService;
    private final AdminAuditLogService adminAuditLogService;
    private final AdminAuditLogRepo adminAuditLogRepo;

    public VerificationApplicationService(VerificationApplicationRepo applicationRepo,
                                          GroupRepo groupRepo,
                                          VerifiedPublisherService verifiedPublisherService,
                                          AgencyAuthorizationService agencyAuthorizationService,
                                          AdminAuditLogService adminAuditLogService,
                                          AdminAuditLogRepo adminAuditLogRepo) {
        this.applicationRepo = applicationRepo;
        this.groupRepo = groupRepo;
        this.verifiedPublisherService = verifiedPublisherService;
        this.agencyAuthorizationService = agencyAuthorizationService;
        this.adminAuditLogService = adminAuditLogService;
        this.adminAuditLogRepo = adminAuditLogRepo;
    }

    @Transactional(readOnly = true)
    public VerificationApplicationDto currentForGroup(String groupId, String callerEmail) {
        Group group = requireGroupAdmin(groupId, callerEmail);
        return applicationRepo.findFirstByGroupIdOrderByUpdatedAtDesc(groupId)
                .map(app -> VerificationApplicationDto.from(app, group))
                .orElse(null);
    }

    @Transactional
    public VerificationApplicationDto submit(String groupId,
                                             String callerEmail,
                                             SubmitVerificationApplicationRequest req) {
        Group group = requireGroupAdmin(groupId, callerEmail);
        VerificationApplication app = applicationRepo.findFirstByGroupIdOrderByUpdatedAtDesc(groupId)
                .orElseGet(VerificationApplication::new);

        if (app.getId() != null && app.getStatus() == VerificationApplication.Status.APPROVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This group already has an approved verification application");
        }

        Instant now = Instant.now();
        app.setGroupId(groupId);
        app.setApplicantEmail(callerEmail);
        app.setAccountType(requireAccountType(req == null ? null : req.accountType()));
        app.setLegalName(trim(req == null ? null : req.legalName(), 180));
        app.setPublicName(trim(req == null ? null : req.publicName(), 240));
        app.setWebsite(trim(req == null ? null : req.website(), 400));
        app.setOfficialEmail(trim(req == null ? null : req.officialEmail(), 180));
        app.setAddressOrJurisdiction(trim(req == null ? null : req.addressOrJurisdiction(), 400));
        app.setServiceArea(trim(req == null ? null : req.serviceArea(), 400));
        app.setPrimaryAdmin(trim(req == null ? null : req.primaryAdmin(), 240));
        app.setBackupContact(trim(req == null ? null : req.backupContact(), 240));
        app.setPostingIntent(trim(req == null ? null : req.postingIntent(), 500));
        app.setProofLinks(trim(req == null ? null : req.proofLinks(), 1000));
        app.setNotes(trim(req == null ? null : req.notes(), 1000));
        app.setStatus(VerificationApplication.Status.SUBMITTED);
        app.setSubmittedAt(now);
        app.setReviewerNotes(null);
        app.setReviewerEmail(null);
        app.setReviewedAt(null);
        app.setVerifiedKind(null);
        app.setApprovedPublisherEmail(null);

        VerificationApplication saved = applicationRepo.save(app);
        return VerificationApplicationDto.from(saved, group);
    }

    @Transactional(readOnly = true)
    public List<VerificationApplicationDto> adminList(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return applicationRepo.findAllByOrderByUpdatedAtDesc()
                    .stream()
                    .map(app -> VerificationApplicationDto.from(app, findGroup(app.getGroupId())))
                    .toList();
        }
        VerificationApplication.Status status = parseStatus(rawStatus);
        return applicationRepo.findByStatusOrderByUpdatedAtDesc(status)
                .stream()
                .map(app -> VerificationApplicationDto.from(app, findGroup(app.getGroupId())))
                .toList();
    }

    /**
     * Aggregate pipeline health for the super-admin readiness card
     * (Phase 5 Slice G). Counts by status + the reviewer-queue / approved
     * / provisioned rollups + median submit&rarr;provision time. Low-volume
     * data (agency applications number in the dozens), so a full scan is fine.
     */
    @Transactional(readOnly = true)
    public AgencyPipelineSummaryDto pipelineSummary() {
        List<VerificationApplication> all = applicationRepo.findAll();

        // Seed every status at 0 so the card renders a stable grid even
        // before any applications exist in a given state.
        Map<String, Long> counts = new LinkedHashMap<>();
        for (VerificationApplication.Status s : VerificationApplication.Status.values()) {
            counts.put(s.name(), 0L);
        }
        List<Double> provisionHours = new ArrayList<>();
        List<Double> claimHours = new ArrayList<>();
        Map<String, Instant> firstClaimedAt = firstClaimedAtByRequest();
        Instant now = Instant.now();
        Instant staleBefore = now.minus(Duration.ofDays(3));
        List<AgencyPipelineSummaryDto.StuckRequest> stuck = new ArrayList<>();
        for (VerificationApplication app : all) {
            VerificationApplication.Status s = app.getStatus() == null
                    ? VerificationApplication.Status.DRAFT : app.getStatus();
            counts.merge(s.name(), 1L, Long::sum);
            if (s == VerificationApplication.Status.PROVISIONED
                    && app.getSubmittedAt() != null && app.getProvisionedAt() != null) {
                double hours = (app.getProvisionedAt().toEpochMilli()
                        - app.getSubmittedAt().toEpochMilli()) / 3_600_000.0;
                if (hours >= 0) provisionHours.add(hours);
            }
            Instant claimedAt = firstClaimedAt.get(String.valueOf(app.getId()));
            if (app.getSubmittedAt() != null && claimedAt != null) {
                double hours = (claimedAt.toEpochMilli() - app.getSubmittedAt().toEpochMilli()) / 3_600_000.0;
                if (hours >= 0) claimHours.add(hours);
            }
            if (isStuckOpen(s, app, staleBefore)) {
                stuck.add(new AgencyPipelineSummaryDto.StuckRequest(
                        app.getId(),
                        firstPresent(app.getPublicName(), app.getLegalName(), app.getOfficialEmail()),
                        s.name(),
                        app.getAssignedConsultantEmail(),
                        app.getSubmittedAt(),
                        app.getUpdatedAt(),
                        Duration.between(app.getSubmittedAt(), now).toDays()));
            }
        }
        long awaitingReview = counts.get("SUBMITTED") + counts.get("IN_REVIEW") + counts.get("NEEDS_INFO");
        return new AgencyPipelineSummaryDto(
                all.size(),
                counts,
                awaitingReview,
                counts.get("APPROVED"),
                counts.get("PROVISIONED"),
                median(provisionHours),
                median(claimHours),
                stuck.size(),
                stuck.stream()
                        .sorted(Comparator.comparingLong(AgencyPipelineSummaryDto.StuckRequest::ageDays).reversed())
                        .limit(8)
                        .toList()
        );
    }

    private Map<String, Instant> firstClaimedAtByRequest() {
        Map<String, Instant> out = new HashMap<>();
        List<AdminAuditLog> rows = adminAuditLogRepo.findByTargetTypeAndActionInOrderByAtAsc(
                "request",
                List.of("CLAIMED", "ASSIGNED_REQUEST"));
        for (AdminAuditLog row : rows) {
            if (row.getTargetId() != null && row.getAt() != null) {
                out.putIfAbsent(row.getTargetId(), row.getAt());
            }
        }
        return out;
    }

    private static boolean isStuckOpen(VerificationApplication.Status status,
                                       VerificationApplication app,
                                       Instant staleBefore) {
        if (app.getSubmittedAt() == null || app.getUpdatedAt() == null) return false;
        return (status == VerificationApplication.Status.SUBMITTED
                || status == VerificationApplication.Status.IN_REVIEW
                || status == VerificationApplication.Status.NEEDS_INFO)
                && app.getUpdatedAt().isBefore(staleBefore);
    }

    private static Double median(List<Double> values) {
        if (values.isEmpty()) return null;
        List<Double> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    @Transactional
    public VerificationApplicationDto adminReview(Long id,
                                                  ReviewVerificationApplicationRequest req,
                                                  String reviewerEmail) {
        VerificationApplication app = applicationRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Verification application not found"));
        VerificationApplication.Status next = parseStatus(req == null ? null : req.status());
        VerificationApplication.Status prev = app.getStatus();
        if (next == VerificationApplication.Status.DRAFT || next == VerificationApplication.Status.SUBMITTED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Reviewer status must be IN_REVIEW, NEEDS_INFO, APPROVED, REJECTED, or PROVISIONED");
        }
        // Provisioning is the handoff AFTER the stamp is granted — only an
        // already-approved application can be marked provisioned.
        if (next == VerificationApplication.Status.PROVISIONED
                && prev != VerificationApplication.Status.APPROVED
                && prev != VerificationApplication.Status.PROVISIONED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only an approved application can be marked provisioned");
        }

        app.setStatus(next);
        app.setReviewerNotes(trim(req == null ? null : req.reviewerNotes(), 1000));
        app.setReviewerEmail(trim(reviewerEmail, 160));
        app.setReviewedAt(Instant.now());

        if (next == VerificationApplication.Status.APPROVED) {
            String publisherEmail = trim(req == null ? null : req.approvedPublisherEmail(), 160);
            if (publisherEmail == null) publisherEmail = app.getApplicantEmail();
            String kind = trim(req == null ? null : req.verifiedKind(), 40);
            if (kind == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "verifiedKind is required when approving");
            }
            app.setApprovedPublisherEmail(publisherEmail);
            app.setVerifiedKind(kind.trim().toLowerCase(Locale.ROOT));
            String serviceArea = trim(req == null ? null : req.publisherServiceArea(), 400);
            if (serviceArea == null) serviceArea = app.getServiceArea();
            String permanentAddress = trim(req == null ? null : req.publisherPermanentAddress(), 400);
            if (permanentAddress == null) permanentAddress = app.getAddressOrJurisdiction();
            String temporaryEventAddress = trim(req == null ? null : req.publisherTemporaryEventAddress(), 400);
            String logoImageUrl = trim(req == null ? null : req.logoImageUrl(), 1024);
            if (logoImageUrl == null) logoImageUrl = app.getLogoImageUrl();
            boolean emergencyPostingEnabled = Boolean.TRUE.equals(
                    req == null ? null : req.emergencyPostingEnabled());
            Group group = findGroup(app.getGroupId());
            Double lat = req == null ? null : req.lat();
            Double lng = req == null ? null : req.lng();
            Double radiusMiles = req == null ? null : req.radiusMiles();
            if (lat == null) lat = app.getDraftLat();
            if (lng == null) lng = app.getDraftLng();
            if (radiusMiles == null) radiusMiles = app.getDraftRadiusMiles();
            agencyAuthorizationService.requireValidGeo(lat, lng, radiusMiles);
            if (group == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "A group must be linked before authorization");
            }
            if (emergencyPostingEnabled) {
                int authorizedAdmins = authorizedAdminCount(group);
                if (authorizedAdmins < 2) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Emergency posting requires at least two authorized group admins");
                }
            }
            verifiedPublisherService.setVerifiedIfUserExists(
                    publisherEmail,
                    true,
                    kind,
                    reviewerEmail,
                    serviceArea,
                    permanentAddress,
                    temporaryEventAddress,
                    emergencyPostingEnabled,
                    app.getGroupId()
            );
            // Phase 5: stamp the agency's jurisdiction on the group (this review
            // path is super-admin token-gated) so it can send geo-targeted alerts.
            if (group != null) {
                List<String> jzips = req == null ? null : req.jurisdictionZips();
                if (jzips != null) {
                    group.setJurisdictionZips(jzips.stream()
                            .filter(z -> z != null && !z.isBlank())
                            .map(String::trim)
                            .distinct()
                            .toList());
                }
                String jtype = trim(req == null ? null : req.jurisdictionType(), 24);
                if (jtype != null) group.setJurisdictionType(jtype.toLowerCase(Locale.ROOT));
                group.setJurisdictionLat(lat);
                group.setJurisdictionLng(lng);
                group.setJurisdictionRadiusMiles(radiusMiles);
                group.setLogoImageUrl(logoImageUrl);
                group.setAgencyAuthorized(true);
                group.setLatitude(String.valueOf(lat));
                group.setLongitude(String.valueOf(lng));
                addAdminIfAbsent(group, publisherEmail);
                groupRepo.save(group);
            }
            app.setDraftLat(lat);
            app.setDraftLng(lng);
            app.setDraftRadiusMiles(radiusMiles);
            app.setPublisherServiceArea(serviceArea);
            app.setPublisherPermanentAddress(permanentAddress);
            app.setPublisherTemporaryEventAddress(temporaryEventAddress);
            app.setLogoImageUrl(logoImageUrl);
            app.setEmergencyPostingEnabled(emergencyPostingEnabled);
        } else if (next == VerificationApplication.Status.PROVISIONED) {
            // Preserve the granted publisher fields (set at APPROVED) — only
            // stamp the handoff moment.
            app.setProvisionedAt(Instant.now());
        } else {
            app.setApprovedPublisherEmail(null);
            app.setVerifiedKind(null);
            app.setPublisherServiceArea(null);
            app.setPublisherPermanentAddress(null);
            app.setPublisherTemporaryEventAddress(null);
            app.setEmergencyPostingEnabled(false);
        }

        VerificationApplication saved = applicationRepo.save(app);
        adminAuditLogService.record(
                reviewerEmail,
                auditAction(next),
                "request",
                String.valueOf(saved.getId()),
                auditSummary(saved, prev, next));
        return VerificationApplicationDto.from(saved, findGroup(saved.getGroupId()));
    }

    private static String auditAction(VerificationApplication.Status next) {
        if (next == VerificationApplication.Status.APPROVED) return "AUTHORIZED";
        if (next == VerificationApplication.Status.PROVISIONED) return "PROVISIONED";
        return "REVIEWED_REQUEST";
    }

    private static String auditSummary(VerificationApplication app,
                                       VerificationApplication.Status prev,
                                       VerificationApplication.Status next) {
        StringBuilder out = new StringBuilder("status ")
                .append(prev == null ? "UNKNOWN" : prev.name())
                .append(" -> ")
                .append(next == null ? "UNKNOWN" : next.name());
        if (app.getGroupId() != null) out.append("; group=").append(app.getGroupId());
        if (app.getApprovedPublisherEmail() != null) {
            out.append("; publisher=").append(app.getApprovedPublisherEmail());
        }
        if (app.getVerifiedKind() != null) out.append("; kind=").append(app.getVerifiedKind());
        if (app.getDraftRadiusMiles() != null) out.append("; radiusMi=").append(app.getDraftRadiusMiles());
        return out.toString();
    }

    private Group requireGroupAdmin(String groupId, String callerEmail) {
        Group group = findGroup(groupId);
        if (group == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found");
        }
        if (!GroupRole.fromGroup(group, callerEmail).isAtLeastAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Group admin or owner role required");
        }
        return group;
    }

    private Group findGroup(String groupId) {
        if (groupId == null || groupId.isBlank()) return null;
        return groupRepo.findByGroupId(groupId).orElse(null);
    }

    private static int authorizedAdminCount(Group group) {
        if (group == null) return 0;
        java.util.Set<String> emails = new java.util.HashSet<>();
        addEmail(emails, group.getOwnerEmail());
        if (group.getAdminEmails() != null) {
            for (String email : group.getAdminEmails()) addEmail(emails, email);
        }
        return emails.size();
    }

    private static void addEmail(java.util.Set<String> emails, String email) {
        if (email == null || email.isBlank()) return;
        emails.add(email.trim().toLowerCase(Locale.ROOT));
    }

    private static void addAdminIfAbsent(Group group, String email) {
        if (group == null || email == null || email.isBlank()) return;
        if (group.getAdminEmails() == null) group.setAdminEmails(new ArrayList<>());
        boolean present = group.getAdminEmails().stream()
                .anyMatch(existing -> existing != null && existing.equalsIgnoreCase(email));
        if (!present) group.getAdminEmails().add(email.trim().toLowerCase(Locale.ROOT));
    }

    private static String requireAccountType(String raw) {
        String value = normalize(raw);
        if (value == null || !ACCOUNT_TYPES.contains(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "accountType must be one of " + ACCOUNT_TYPES);
        }
        return value;
    }

    private static VerificationApplication.Status parseStatus(String raw) {
        String value = normalize(raw);
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status is required");
        }
        try {
            return VerificationApplication.Status.valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status: " + raw);
        }
    }

    private static String normalize(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return value.isBlank() ? null : value;
    }

    private static String firstPresent(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String trim(String raw, int max) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isBlank()) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
