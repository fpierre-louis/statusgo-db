package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.GroupRole;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.VerificationApplication;
import io.sitprep.sitprepapi.dto.ReviewVerificationApplicationRequest;
import io.sitprep.sitprepapi.dto.SubmitVerificationApplicationRequest;
import io.sitprep.sitprepapi.dto.VerificationApplicationDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.VerificationApplicationRepo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
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

    public VerificationApplicationService(VerificationApplicationRepo applicationRepo,
                                          GroupRepo groupRepo,
                                          VerifiedPublisherService verifiedPublisherService) {
        this.applicationRepo = applicationRepo;
        this.groupRepo = groupRepo;
        this.verifiedPublisherService = verifiedPublisherService;
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

    @Transactional
    public VerificationApplicationDto adminReview(Long id,
                                                  ReviewVerificationApplicationRequest req,
                                                  String reviewerEmail) {
        VerificationApplication app = applicationRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Verification application not found"));
        VerificationApplication.Status next = parseStatus(req == null ? null : req.status());
        if (next == VerificationApplication.Status.DRAFT || next == VerificationApplication.Status.SUBMITTED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Reviewer status must be IN_REVIEW, NEEDS_INFO, APPROVED, or REJECTED");
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
            boolean emergencyPostingEnabled = Boolean.TRUE.equals(
                    req == null ? null : req.emergencyPostingEnabled());
            verifiedPublisherService.setVerified(
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
            app.setPublisherServiceArea(serviceArea);
            app.setPublisherPermanentAddress(permanentAddress);
            app.setPublisherTemporaryEventAddress(temporaryEventAddress);
            app.setEmergencyPostingEnabled(emergencyPostingEnabled);
        } else {
            app.setApprovedPublisherEmail(null);
            app.setVerifiedKind(null);
            app.setPublisherServiceArea(null);
            app.setPublisherPermanentAddress(null);
            app.setPublisherTemporaryEventAddress(null);
            app.setEmergencyPostingEnabled(false);
        }

        VerificationApplication saved = applicationRepo.save(app);
        return VerificationApplicationDto.from(saved, findGroup(saved.getGroupId()));
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

    private static String trim(String raw, int max) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isBlank()) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
