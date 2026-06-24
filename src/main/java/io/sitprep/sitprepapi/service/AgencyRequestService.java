package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.VerificationApplication;
import io.sitprep.sitprepapi.domain.VerificationApplicationNote;
import io.sitprep.sitprepapi.dto.AddAgencyRequestNoteRequest;
import io.sitprep.sitprepapi.dto.AgencyRequestDetailDto;
import io.sitprep.sitprepapi.dto.AgencyRequestDto;
import io.sitprep.sitprepapi.dto.AssignAgencyRequestRequest;
import io.sitprep.sitprepapi.dto.AuthorizeAgencyRequestRequest;
import io.sitprep.sitprepapi.dto.BulkAssignAgencyRequestsRequest;
import io.sitprep.sitprepapi.dto.CreateAgencyRequestRequest;
import io.sitprep.sitprepapi.dto.PatchAgencyRequestRequest;
import io.sitprep.sitprepapi.dto.ReviewVerificationApplicationRequest;
import io.sitprep.sitprepapi.dto.SaveAgencyRequestDraftRequest;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.VerificationApplicationNoteRepo;
import io.sitprep.sitprepapi.repo.VerificationApplicationRepo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class AgencyRequestService {

    private static final Set<VerificationApplication.Status> OPEN_STATUSES = Set.of(
            VerificationApplication.Status.SUBMITTED,
            VerificationApplication.Status.IN_REVIEW,
            VerificationApplication.Status.NEEDS_INFO);

    private static final Set<VerificationApplication.Status> CLAIMABLE_STATUSES = Set.of(
            VerificationApplication.Status.SUBMITTED,
            VerificationApplication.Status.NEEDS_INFO);

    private final VerificationApplicationRepo applicationRepo;
    private final VerificationApplicationNoteRepo noteRepo;
    private final GroupRepo groupRepo;
    private final VerificationApplicationService verificationApplicationService;
    private final AgencyAuthorizationService agencyAuthorizationService;
    private final AdminAuditLogService adminAuditLogService;

    public AgencyRequestService(VerificationApplicationRepo applicationRepo,
                                VerificationApplicationNoteRepo noteRepo,
                                GroupRepo groupRepo,
                                VerificationApplicationService verificationApplicationService,
                                AgencyAuthorizationService agencyAuthorizationService,
                                AdminAuditLogService adminAuditLogService) {
        this.applicationRepo = applicationRepo;
        this.noteRepo = noteRepo;
        this.groupRepo = groupRepo;
        this.verificationApplicationService = verificationApplicationService;
        this.agencyAuthorizationService = agencyAuthorizationService;
        this.adminAuditLogService = adminAuditLogService;
    }

    @Transactional
    public AgencyRequestDto create(CreateAgencyRequestRequest req, String submitterEmail) {
        String officialEmail = requireEmail(req == null ? null : req.officialEmail(), "officialEmail");
        String agencyName = requireText(req == null ? null : req.agencyName(), "agencyName", 240);
        String actor = normalizeEmail(submitterEmail);
        if (actor == null) actor = officialEmail;

        var duplicate = applicationRepo
                .findFirstByOfficialEmailIgnoreCaseAndPublicNameIgnoreCaseAndStatusInOrderByUpdatedAtDesc(
                        officialEmail, agencyName, OPEN_STATUSES);
        if (duplicate.isPresent()) {
            return toDto(duplicate.get());
        }

        Instant now = Instant.now();
        VerificationApplication app = new VerificationApplication();
        app.setGroupId(null);
        app.setApplicantEmail(actor);
        app.setSubmitterEmail(actor);
        app.setSource("REQUEST");
        app.setAccountType("official-agency");
        app.setLegalName(agencyName);
        app.setPublicName(agencyName);
        app.setOfficialEmail(officialEmail);
        app.setPrimaryAdmin(trim(req == null ? null : req.contactName(), 240));
        app.setBackupContact(trim(req == null ? null : req.role(), 240));
        app.setPostingIntent(trim(req == null ? null : req.message(), 500));
        app.setStatedJurisdiction(trim(req == null ? null : req.statedJurisdiction(), 400));
        app.setAddressOrJurisdiction(trim(req == null ? null : req.statedJurisdiction(), 400));
        app.setNotes(trim(req == null ? null : req.message(), 1000));
        app.setStatus(VerificationApplication.Status.SUBMITTED);
        app.setSubmittedAt(now);
        VerificationApplication saved = applicationRepo.save(app);
        adminAuditLogService.record(
                actor,
                "REQUEST_CREATED",
                "request",
                String.valueOf(saved.getId()),
                "agency=" + agencyName + "; officialEmail=" + officialEmail);
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public List<AgencyRequestDto> list(String rawState, Boolean mine, String actorEmail) {
        VerificationApplication.Status state = parseStatus(rawState, true);
        String actor = normalizeEmail(actorEmail);
        List<VerificationApplication> rows = state == null
                ? applicationRepo.findAllByOrderByUpdatedAtDesc()
                : applicationRepo.findByStatusOrderByUpdatedAtDesc(state);
        return rows.stream()
                .filter(app -> !Boolean.TRUE.equals(mine)
                        || sameEmail(app.getAssignedConsultantEmail(), actor)
                        || app.getAssignedConsultantEmail() == null)
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public AgencyRequestDetailDto detail(Long id) {
        VerificationApplication app = requireApp(id);
        return AgencyRequestDetailDto.from(app, findGroup(app.getGroupId()),
                noteRepo.findByApplicationIdOrderByCreatedAtAsc(id));
    }

    @Transactional
    public AgencyRequestDto claim(Long id, String actorEmail) {
        String actor = requireActor(actorEmail);
        int updated = applicationRepo.claimIfUnassigned(
                id,
                actor,
                VerificationApplication.Status.IN_REVIEW,
                CLAIMABLE_STATUSES,
                Instant.now());
        VerificationApplication app = requireApp(id);
        if (updated == 0 && !sameEmail(app.getAssignedConsultantEmail(), actor)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already claimed");
        }
        adminAuditLogService.record(
                actor,
                "CLAIMED",
                "request",
                String.valueOf(id),
                "assigned=" + actor);
        return toDto(app);
    }

    @Transactional
    public AgencyRequestDto assign(Long id, AssignAgencyRequestRequest req, String actorEmail) {
        String actor = requireActor(actorEmail);
        String consultant = requireEmail(req == null ? null : req.consultantEmail(), "consultantEmail");
        VerificationApplication app = requireApp(id);
        String previous = app.getAssignedConsultantEmail();
        app.setAssignedConsultantEmail(consultant);
        if (app.getStatus() == VerificationApplication.Status.SUBMITTED) {
            app.setStatus(VerificationApplication.Status.IN_REVIEW);
        }
        VerificationApplication saved = applicationRepo.save(app);
        adminAuditLogService.record(
                actor,
                "ASSIGNED_REQUEST",
                "request",
                String.valueOf(id),
                "assigned " + nullSafe(previous) + " -> " + consultant);
        return toDto(saved);
    }

    @Transactional
    public List<AgencyRequestDto> bulkAssign(BulkAssignAgencyRequestsRequest req, String actorEmail) {
        String actor = requireActor(actorEmail);
        String consultant = requireEmail(req == null ? null : req.consultantEmail(), "consultantEmail");
        List<Long> ids = req == null || req.ids() == null ? List.of() : req.ids().stream()
                .filter(id -> id != null)
                .distinct()
                .limit(100)
                .toList();
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ids are required");
        }
        return ids.stream()
                .map(id -> assign(id, new AssignAgencyRequestRequest(consultant), actor))
                .toList();
    }

    @Transactional
    public AgencyRequestDetailDto addNote(Long id, AddAgencyRequestNoteRequest req, String actorEmail) {
        String actor = requireActor(actorEmail);
        requireApp(id);
        String body = requireText(req == null ? null : req.note(), "note", 1000);
        VerificationApplicationNote note = new VerificationApplicationNote();
        note.setApplicationId(id);
        note.setAuthorEmail(actor);
        note.setNote(body);
        noteRepo.save(note);
        adminAuditLogService.record(
                actor,
                "ADDED_NOTE",
                "request",
                String.valueOf(id),
                trim(body, 120));
        return detail(id);
    }

    @Transactional
    public AgencyRequestDto patchState(Long id, PatchAgencyRequestRequest req, String actorEmail) {
        String actor = requireActor(actorEmail);
        VerificationApplication.Status next = parseStatus(req == null ? null : req.state(), false);
        if (next == VerificationApplication.Status.DRAFT
                || next == VerificationApplication.Status.SUBMITTED
                || next == VerificationApplication.Status.APPROVED
                || next == VerificationApplication.Status.PROVISIONED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "state must be IN_REVIEW, NEEDS_INFO, or REJECTED");
        }
        VerificationApplication app = requireApp(id);
        VerificationApplication.Status previous = app.getStatus();
        app.setStatus(next);
        app.setReviewerEmail(actor);
        app.setReviewerNotes(trim(req == null ? null : req.reviewerNotes(), 1000));
        app.setReviewedAt(Instant.now());
        VerificationApplication saved = applicationRepo.save(app);
        adminAuditLogService.record(
                actor,
                "REQUEST_STATE_CHANGED",
                "request",
                String.valueOf(id),
                "status " + previous + " -> " + next);
        return toDto(saved);
    }

    @Transactional
    public AgencyRequestDto saveDraft(Long id, SaveAgencyRequestDraftRequest req, String actorEmail) {
        String actor = requireActor(actorEmail);
        VerificationApplication app = requireApp(id);
        app.setVerifiedKind(trim(req == null ? null : req.verifiedKind(), 40));
        app.setApprovedPublisherEmail(trim(req == null ? null : req.publisherEmail(), 160));
        app.setEmergencyPostingEnabled(Boolean.TRUE.equals(req == null ? null : req.emergencyPosting()));
        app.setPublisherServiceArea(trim(req == null ? null : req.publisherServiceArea(), 400));
        app.setPublisherPermanentAddress(trim(req == null ? null : req.publisherPermanentAddress(), 400));
        app.setPublisherTemporaryEventAddress(trim(req == null ? null : req.publisherTemporaryEventAddress(), 400));
        app.setLogoImageUrl(trim(req == null ? null : req.logoImageUrl(), 1024));
        Double lat = req == null ? null : req.lat();
        Double lng = req == null ? null : req.lng();
        Double radiusMiles = req == null ? null : req.radiusMiles();
        if (lat != null || lng != null || radiusMiles != null) {
            agencyAuthorizationService.requireValidGeo(lat, lng, radiusMiles);
            app.setDraftLat(lat);
            app.setDraftLng(lng);
            app.setDraftRadiusMiles(radiusMiles);
        }
        VerificationApplication saved = applicationRepo.save(app);
        adminAuditLogService.record(
                actor,
                "SAVED_REQUEST_DRAFT",
                "request",
                String.valueOf(id),
                "publisher=" + nullSafe(saved.getApprovedPublisherEmail())
                        + "; kind=" + nullSafe(saved.getVerifiedKind())
                        + "; radiusMi=" + nullSafe(saved.getDraftRadiusMiles()));
        return toDto(saved);
    }

    @Transactional
    public AgencyRequestDto authorize(Long id, AuthorizeAgencyRequestRequest req, String actorEmail) {
        String actor = requireActor(actorEmail);
        if (!Boolean.TRUE.equals(req == null ? null : req.confirm())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "confirm must be true");
        }
        VerificationApplication app = requireApp(id);
        if (app.getStatus() == VerificationApplication.Status.APPROVED
                || app.getStatus() == VerificationApplication.Status.PROVISIONED) {
            return toDto(app);
        }
        Double lat = req.lat() == null ? app.getDraftLat() : req.lat();
        Double lng = req.lng() == null ? app.getDraftLng() : req.lng();
        Double radiusMiles = req.radiusMiles() == null ? app.getDraftRadiusMiles() : req.radiusMiles();
        agencyAuthorizationService.requireValidGeo(lat, lng, radiusMiles);
        if (isBlank(app.getVerifiedKind())) {
            app.setVerifiedKind(firstPresent(app.getAccountType(), "official-agency"));
        }
        if (isBlank(app.getApprovedPublisherEmail())) {
            app.setApprovedPublisherEmail(requireEmail(
                    firstPresent(app.getOfficialEmail(), app.getApplicantEmail()), "publisherEmail"));
        }
        if (isBlank(app.getPublisherServiceArea())) {
            app.setPublisherServiceArea(firstPresent(app.getStatedJurisdiction(), app.getAddressOrJurisdiction()));
        }
        applicationRepo.save(app);
        ensureAgencyWorkspace(app, actor);
        var review = new ReviewVerificationApplicationRequest(
                VerificationApplication.Status.APPROVED.name(),
                app.getReviewerNotes(),
                app.getVerifiedKind(),
                app.getApprovedPublisherEmail(),
                app.getPublisherServiceArea(),
                app.getPublisherPermanentAddress(),
                app.getPublisherTemporaryEventAddress(),
                app.getLogoImageUrl(),
                app.isEmergencyPostingEnabled(),
                null,
                null,
                lat,
                lng,
                radiusMiles);
        return AgencyRequestDto.from(
                requireEntity(verificationApplicationService.adminReview(id, review, actor).id()),
                findGroup(app.getGroupId()));
    }

    private void ensureAgencyWorkspace(VerificationApplication app, String actor) {
        if (app.getGroupId() != null && !app.getGroupId().isBlank()) return;
        String groupId = UUID.randomUUID().toString();
        String name = firstPresent(app.getPublicName(), app.getLegalName(), app.getOfficialEmail(), "Agency");
        String ownerEmail = requireEmail(firstPresent(
                app.getApprovedPublisherEmail(),
                app.getOfficialEmail(),
                app.getApplicantEmail()), "publisherEmail");
        Group group = new Group();
        group.setGroupId(groupId);
        group.setCreatedAt(Instant.now());
        group.setUpdatedAt(Instant.now());
        group.setGroupName(name);
        group.setGroupCode(defaultCode(name, groupId));
        group.setGroupType(firstPresent(app.getVerifiedKind(), app.getAccountType(), "official-agency"));
        group.setJurisdictionType(firstPresent(app.getVerifiedKind(), app.getAccountType(), "public-safety"));
        group.setOwnerEmail(ownerEmail);
        group.setOwnerName(firstPresent(app.getPrimaryAdmin(), app.getLegalName(), app.getPublicName()));
        group.setLogoImageUrl(trim(app.getLogoImageUrl(), 1024));
        group.setMemberCount(0);
        group.setPrivacy("Public");
        group.setAdminEmails(new ArrayList<>());
        group.setMemberEmails(new ArrayList<>());
        group.setPendingMemberEmails(new ArrayList<>());
        addAdminIfAbsent(group, ownerEmail);
        addAdminIfAbsent(group, app.getSubmitterEmail());
        groupRepo.save(group);
        app.setGroupId(groupId);
        applicationRepo.save(app);
        adminAuditLogService.record(
                actor,
                "CREATED_AGENCY_WORKSPACE",
                "request",
                String.valueOf(app.getId()),
                "group=" + groupId + "; agency=" + name);
    }

    @Transactional
    public AgencyRequestDto provision(Long id, String actorEmail) {
        String actor = requireActor(actorEmail);
        var review = new ReviewVerificationApplicationRequest(
                VerificationApplication.Status.PROVISIONED.name(),
                null, null, null, null, null, null, null, null, null, null, null, null, null);
        return AgencyRequestDto.from(
                requireEntity(verificationApplicationService.adminReview(id, review, actor).id()),
                findGroup(requireApp(id).getGroupId()));
    }

    private AgencyRequestDto toDto(VerificationApplication app) {
        return AgencyRequestDto.from(app, findGroup(app.getGroupId()));
    }

    private VerificationApplication requireApp(Long id) {
        if (id == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id required");
        return applicationRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
    }

    private VerificationApplication requireEntity(Long id) {
        return applicationRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
    }

    private Group findGroup(String groupId) {
        if (groupId == null || groupId.isBlank()) return null;
        return groupRepo.findByGroupId(groupId).orElse(null);
    }

    private static VerificationApplication.Status parseStatus(String raw, boolean allowBlank) {
        if (raw == null || raw.isBlank()) return allowBlank ? null : missingStatus();
        try {
            return VerificationApplication.Status.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown state: " + raw);
        }
    }

    private static VerificationApplication.Status missingStatus() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "state is required");
    }

    private static String requireActor(String email) {
        String actor = normalizeEmail(email);
        if (actor == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated request required");
        }
        return actor;
    }

    private static String requireEmail(String raw, String field) {
        String value = normalizeEmail(raw);
        if (value == null || !value.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be a valid email");
        }
        return value;
    }

    private static String requireText(String raw, String field, int max) {
        String value = trim(raw, max);
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value;
    }

    private static String normalizeEmail(String raw) {
        String value = trim(raw, 320);
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static boolean sameEmail(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private static String trim(String raw, int max) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isBlank()) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String nullSafe(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
    }

    private static String nullSafe(Double value) {
        return value == null ? "(none)" : String.valueOf(value);
    }

    private static void addAdminIfAbsent(Group group, String rawEmail) {
        String email = normalizeEmail(rawEmail);
        if (email == null) return;
        if (group.getAdminEmails() == null) group.setAdminEmails(new ArrayList<>());
        boolean present = group.getAdminEmails().stream()
                .anyMatch(existing -> existing != null && existing.equalsIgnoreCase(email));
        if (!present) group.getAdminEmails().add(email);
    }

    private static String defaultCode(String name, String groupId) {
        String prefix = name == null ? "AGENCY" : name.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (prefix.isBlank()) prefix = "AGENCY";
        if (prefix.length() > 12) prefix = prefix.substring(0, 12);
        return prefix + "-" + groupId.substring(0, Math.min(6, groupId.length())).toUpperCase(Locale.ROOT);
    }

    private static String firstPresent(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
