package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.AdminAgencyDto;
import io.sitprep.sitprepapi.dto.CreateAdminAgencyRequest;
import io.sitprep.sitprepapi.dto.RadiusPreviewDto;
import io.sitprep.sitprepapi.dto.RadiusPreviewRequest;
import io.sitprep.sitprepapi.dto.SaveAgencyGeoRequest;
import io.sitprep.sitprepapi.repo.GroupRepo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AdminAgencyService {

    private static final int PREVIEW_RECENCY_DAYS = 30;

    private final GroupRepo groupRepo;
    private final UserGeoService userGeoService;
    private final AgencyAuthorizationService agencyAuthorizationService;
    private final VerifiedPublisherService verifiedPublisherService;
    private final AdminAuditLogService adminAuditLogService;

    public AdminAgencyService(GroupRepo groupRepo,
                              UserGeoService userGeoService,
                              AgencyAuthorizationService agencyAuthorizationService,
                              VerifiedPublisherService verifiedPublisherService,
                              AdminAuditLogService adminAuditLogService) {
        this.groupRepo = groupRepo;
        this.userGeoService = userGeoService;
        this.agencyAuthorizationService = agencyAuthorizationService;
        this.verifiedPublisherService = verifiedPublisherService;
        this.adminAuditLogService = adminAuditLogService;
    }

    @Transactional(readOnly = true)
    public List<AdminAgencyDto> list() {
        return groupRepo.findAuthorizedAgencies().stream()
                .map(AdminAgencyDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public RadiusPreviewDto preview(RadiusPreviewRequest req) {
        agencyAuthorizationService.requireValidGeo(
                req == null ? null : req.lat(),
                req == null ? null : req.lng(),
                req == null ? null : req.radiusMiles());
        Instant since = Instant.now().minus(PREVIEW_RECENCY_DAYS, ChronoUnit.DAYS);
        int count = userGeoService.countWithinRadiusMiles(req.lat(), req.lng(), req.radiusMiles(), since);
        return new RadiusPreviewDto(count);
    }

    @Transactional
    public AdminAgencyDto create(CreateAdminAgencyRequest req, String actorEmail) {
        String groupId = trim(req == null ? null : req.groupId(), 80);
        Group group = groupId == null ? new Group() : groupRepo.findByGroupId(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found"));

        boolean creating = group.getGroupId() == null;
        if (creating) {
            group.setGroupId(UUID.randomUUID().toString());
            group.setCreatedAt(Instant.now());
            group.setMemberCount(0);
            group.setPrivacy("Public");
            group.setAdminEmails(new ArrayList<>());
            group.setMemberEmails(new ArrayList<>());
            group.setPendingMemberEmails(new ArrayList<>());
        }

        String name = requireText(req == null ? null : req.groupName(), "groupName", 240);
        String ownerEmail = requireEmail(req == null ? null : req.ownerEmail(), "ownerEmail");
        group.setGroupName(name);
        if (group.getGroupCode() == null || group.getGroupCode().isBlank()) {
            group.setGroupCode(defaultCode(name, group.getGroupId()));
        }
        group.setGroupType(firstPresent(trim(req == null ? null : req.kind(), 64), "Agency"));
        group.setOwnerEmail(ownerEmail);
        group.setOwnerName(trim(req == null ? null : req.ownerName(), 160));
        group.setLogoImageUrl(trim(req == null ? null : req.logoImageUrl(), 1024));
        group.setJurisdictionType(normalizeJurisdictionType(req == null ? null : req.jurisdictionType()));
        stampGeo(group, req == null ? null : req.lat(), req == null ? null : req.lng(),
                req == null ? null : req.radiusMiles());
        group.setAgencyAuthorized(true);
        addAdminIfAbsent(group, ownerEmail);
        group.setUpdatedAt(Instant.now());

        Group saved = groupRepo.save(group);
        verifiedPublisherService.setVerifiedIfUserExists(
                ownerEmail,
                true,
                verifiedKind(req == null ? null : req.kind()),
                actorEmail,
                saved.getGroupName(),
                null,
                null,
                true,
                saved.getGroupId());
        adminAuditLogService.record(
                actorEmail,
                creating ? "CREATED_AGENCY" : "AUTHORIZED_AGENCY",
                "group",
                saved.getGroupId(),
                "agency=" + saved.getGroupName()
                        + "; radiusMi=" + saved.getJurisdictionRadiusMiles());
        return AdminAgencyDto.from(saved);
    }

    @Transactional
    public AdminAgencyDto updateGeo(String groupId, SaveAgencyGeoRequest req, String actorEmail) {
        Group group = groupRepo.findByGroupId(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found"));
        String before = geoSummary(group);
        stampGeo(group, req == null ? null : req.lat(), req == null ? null : req.lng(),
                req == null ? null : req.radiusMiles());
        group.setAgencyAuthorized(true);
        group.setUpdatedAt(Instant.now());
        Group saved = groupRepo.save(group);
        adminAuditLogService.record(
                actorEmail,
                "EDITED_GEO",
                "group",
                saved.getGroupId(),
                before + " -> " + geoSummary(saved));
        return AdminAgencyDto.from(saved);
    }

    private void stampGeo(Group group, Double lat, Double lng, Double radiusMiles) {
        agencyAuthorizationService.requireValidGeo(lat, lng, radiusMiles);
        group.setJurisdictionLat(lat);
        group.setJurisdictionLng(lng);
        group.setJurisdictionRadiusMiles(radiusMiles);
        group.setLatitude(String.valueOf(lat));
        group.setLongitude(String.valueOf(lng));
    }

    private static String defaultCode(String name, String groupId) {
        String prefix = name == null ? "AGENCY" : name.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (prefix.isBlank()) prefix = "AGENCY";
        if (prefix.length() > 12) prefix = prefix.substring(0, 12);
        return prefix + "-" + groupId.substring(0, Math.min(6, groupId.length())).toUpperCase(Locale.ROOT);
    }

    private static void addAdminIfAbsent(Group group, String email) {
        if (email == null) return;
        if (group.getAdminEmails() == null) group.setAdminEmails(new ArrayList<>());
        boolean present = group.getAdminEmails().stream()
                .anyMatch(existing -> existing != null && existing.equalsIgnoreCase(email));
        if (!present) group.getAdminEmails().add(email);
    }

    private static String geoSummary(Group group) {
        return "lat=" + group.getJurisdictionLat()
                + "; lng=" + group.getJurisdictionLng()
                + "; radiusMi=" + group.getJurisdictionRadiusMiles();
    }

    private static String requireText(String raw, String field, int max) {
        String value = trim(raw, max);
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value;
    }

    private static String requireEmail(String raw, String field) {
        String value = trim(raw, 320);
        if (value == null || !value.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be a valid email");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String normalizeJurisdictionType(String raw) {
        String value = trim(raw, 24);
        return value == null ? "other" : value.toLowerCase(Locale.ROOT);
    }

    private static String verifiedKind(String raw) {
        String value = trim(raw, 40);
        return value == null ? "official-agency" : value.toLowerCase(Locale.ROOT);
    }

    private static String firstPresent(String... values) {
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
