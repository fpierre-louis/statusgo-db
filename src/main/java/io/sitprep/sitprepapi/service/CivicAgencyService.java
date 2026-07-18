package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.CivicStatus;
import io.sitprep.sitprepapi.constant.GroupRole;
import io.sitprep.sitprepapi.domain.CivicCoverageGap;
import io.sitprep.sitprepapi.domain.CivicReportAgency;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.repo.CivicCoverageGapRepo;
import io.sitprep.sitprepapi.repo.CivicReportAgencyRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Civic epic Slice 2 — the multi-agency civic tagging + claim/release engine.
 * Owns the {@code civic_report_agency} join + the {@code civic_coverage_gap}
 * orphan ledger, and the gating that {@link PostService} calls at the civic
 * status/spawn boundaries.
 *
 * <p>Model (locked owner decisions):</p>
 * <ul>
 *   <li><b>D2 auto-derive:</b> at create every covering authorized agency is
 *       auto-tagged (resolver), the filer confirms/adjusts.</li>
 *   <li><b>Decision 1:</b> a deselected auto-tag is a tombstone ({@code active=false},
 *       never deleted); citizen additions are limited to the authorized set.</li>
 *   <li><b>Decision 3:</b> ACKNOWLEDGE is open to any active-tagged agency;
 *       SCHEDULE/RESOLVE + work-order spawn + merge require the CLAIM.</li>
 *   <li><b>Decision 4:</b> release returns the report to unclaimed, claimable by
 *       any tagged agency; no auto-reassign.</li>
 *   <li><b>Decision 2:</b> a zero-coverage report still persists; its zip is
 *       recorded as a coverage-gap demand signal (ghost group if one covers the
 *       zip, else the group-less {@code civic_coverage_gap} ledger).</li>
 * </ul>
 */
@Service
public class CivicAgencyService {

    private static final Logger log = LoggerFactory.getLogger(CivicAgencyService.class);

    private final CivicReportAgencyRepo tagRepo;
    private final CivicCoverageGapRepo gapRepo;
    private final GroupRepo groupRepo;
    private final PostRepo taskRepo;
    private final AgencyJurisdictionService jurisdiction;
    private final GhostTenantService ghostTenant;

    public CivicAgencyService(CivicReportAgencyRepo tagRepo, CivicCoverageGapRepo gapRepo,
                              GroupRepo groupRepo, PostRepo taskRepo,
                              AgencyJurisdictionService jurisdiction, GhostTenantService ghostTenant) {
        this.tagRepo = tagRepo;
        this.gapRepo = gapRepo;
        this.groupRepo = groupRepo;
        this.taskRepo = taskRepo;
        this.jurisdiction = jurisdiction;
        this.ghostTenant = ghostTenant;
    }

    /** A tag as the queue sees it — resolved name + claim state + source. */
    public record AgencyTag(String agencyGroupId, String name, boolean claimed, String tagSource) {}

    /** Claim/release outcome for the endpoint response. */
    public record ClaimResult(Long postId, String claimState, String claimingAgencyGroupId) {}

    // ─────────────────────────────────────────────────────────────────────
    // Create-path auto-derive (D2)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Resolve + persist a civic report's agency tags at create (post-save, so the
     * generated id exists). Auto-derives every covering authorized agency, then
     * reconciles against the filer's confirmed set: covered-and-kept → active
     * {@code auto}; covered-but-deselected → {@code active=false} tombstone;
     * confirmed-but-not-covered (a citizen addition, authorized only) →
     * {@code citizen_added}. Dual-writes the {@code taggedAgencyGroupId} mirror.
     * When no active tag results, records the orphan coverage-gap signal.
     */
    @Transactional
    public void applyCreateTags(Post report, String postcode) {
        Long postId = report.getId();
        List<Group> covering = jurisdiction.agenciesCovering(
                report.getLatitude(), report.getLongitude(), postcode);
        Set<String> coveringIds = covering.stream()
                .map(Group::getGroupId).filter(id -> id != null)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Confirmed set: an explicit civicAgencyIds list wins (even empty = "all
        // deselected"); else the legacy single tag; else pure auto (all covering).
        Set<String> confirmed;
        if (report.getCivicAgencyIds() != null) {
            confirmed = report.getCivicAgencyIds().stream()
                    .filter(id -> id != null && !id.isBlank()).map(String::trim)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } else if (report.getTaggedAgencyGroupId() != null && !report.getTaggedAgencyGroupId().isBlank()) {
            confirmed = new LinkedHashSet<>(List.of(report.getTaggedAgencyGroupId().trim()));
        } else {
            confirmed = new LinkedHashSet<>(coveringIds);
        }

        List<String> activeOrdered = new ArrayList<>();

        // Covering agencies: active auto if kept, tombstone if deselected.
        for (String cid : coveringIds) {
            boolean kept = confirmed.contains(cid);
            upsertTag(postId, cid, "auto", kept);
            if (kept) activeOrdered.add(cid);
        }
        // Citizen additions (confirmed but not covering) — authorized only.
        for (String cid : confirmed) {
            if (coveringIds.contains(cid)) continue;
            if (!isAuthorizedAgency(cid)) {
                log.info("[civic] dropping non-authorized tag {} on report {}", cid, postId);
                continue;
            }
            upsertTag(postId, cid, "citizen_added", true);
            activeOrdered.add(cid);
        }

        // Dual-write the display mirror (first active tag) + orphan hook.
        report.setTaggedAgencyGroupId(activeOrdered.isEmpty() ? null : activeOrdered.get(0));
        taskRepo.save(report);
        if (activeOrdered.isEmpty()) {
            recordCoverageGap(report, postcode);
        }
    }

    private void upsertTag(Long postId, String agencyGroupId, String source, boolean active) {
        CivicReportAgency row = tagRepo.findByPostIdAndAgencyGroupId(postId, agencyGroupId)
                .orElseGet(CivicReportAgency::new);
        row.setPostId(postId);
        row.setAgencyGroupId(agencyGroupId);
        row.setTagSource(source);
        row.setActive(active);
        tagRepo.save(row);
    }

    /**
     * Orphan hook (decision 2). The report persists regardless — this only
     * records the demand signal. If a GHOST group covers the zip, reuse the
     * existing distinct-resident demand ledger; otherwise accumulate a group-less
     * zip row in {@code civic_coverage_gap}. Never throws into the create path.
     */
    private void recordCoverageGap(Post report, String postcode) {
        String zip = postcode == null ? null : postcode.trim();
        if (zip == null || zip.isEmpty()) return; // nothing to key on
        try {
            Optional<Group> ghost = groupRepo.findByJurisdictionZip(zip).stream()
                    .filter(g -> "GHOST".equalsIgnoreCase(g.getClaimState()))
                    .findFirst();
            if (ghost.isPresent()) {
                ghostTenant.recordDemandSignal(ghost.get().getGroupId(), report.getRequesterEmail());
                return;
            }
        } catch (RuntimeException e) {
            log.debug("[civic] ghost demand signal skipped for zip {}: {}", zip, e.getMessage());
        }
        try {
            CivicCoverageGap gap = gapRepo.findByZip(zip).orElseGet(() -> {
                CivicCoverageGap g = new CivicCoverageGap();
                g.setZip(zip);
                g.setReportCount(0);
                return g;
            });
            gap.setLastCategory(report.getCivicCategory());
            gap.setReportCount(gap.getReportCount() + 1);
            gap.setLastSeen(Instant.now());
            gapRepo.save(gap);
        } catch (DataIntegrityViolationException raced) {
            log.debug("[civic] coverage-gap upsert raced for zip {}", zip);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Claim / release
    // ─────────────────────────────────────────────────────────────────────

    /** An authorized, actively-tagged agency takes the report. 409 if already claimed. */
    @Transactional
    public ClaimResult claim(Long postId, String agencyGroupId, String callerEmail) {
        Post report = mustBeCivicReport(postId);
        CivicReportAgency tag = tagRepo.findByPostIdAndAgencyGroupId(postId, agencyGroupId)
                .filter(CivicReportAgency::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "This report is not tagged to your agency"));
        if (tagRepo.existsByPostIdAndClaimedTrue(postId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Report is already claimed");
        }
        tag.setClaimed(true);
        tag.setClaimedAt(Instant.now());
        tag.setClaimedByEmail(callerEmail == null ? null : callerEmail.trim().toLowerCase());
        tag.setReleasedAt(null);
        try {
            tagRepo.saveAndFlush(tag);
        } catch (DataIntegrityViolationException raced) {
            // Lost the race to the partial-unique one-claim index.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Report was just claimed by another agency");
        }
        report.setClaimingAgencyGroupId(agencyGroupId);
        taskRepo.save(report);
        return new ClaimResult(postId, "claimed", agencyGroupId);
    }

    /** The claiming agency releases → unclaimed, claimable by other tagged agencies. */
    @Transactional
    public ClaimResult release(Long postId, String agencyGroupId, String callerEmail) {
        mustBeCivicReport(postId);
        CivicReportAgency claim = tagRepo.findByPostIdAndClaimedTrue(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Report is not claimed"));
        if (!claim.getAgencyGroupId().equalsIgnoreCase(agencyGroupId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the claiming agency can release this report");
        }
        claim.setClaimed(false);
        claim.setReleasedAt(Instant.now());
        tagRepo.save(claim);
        Post report = taskRepo.findById(postId).orElseThrow();
        report.setClaimingAgencyGroupId(null);
        taskRepo.save(report);
        return new ClaimResult(postId, "unclaimed", null);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Gating (called from PostService's civic-status + spawn boundaries)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Enforce decision 3 for a civic-status advance. ACKNOWLEDGED is open to any
     * admin of an active-tagged agency; SCHEDULED/RESOLVED require the caller to
     * be an admin of the CLAIMING agency (and the report must be claimed).
     */
    public void requireCanAdvanceCivic(Post report, CivicStatus to, String callerEmail) {
        if (to == CivicStatus.SCHEDULED || to == CivicStatus.RESOLVED) {
            requireClaimingAgencyAdmin(report, callerEmail);
        } else {
            // REPORTED can't be a forward target; ACKNOWLEDGED → any tagged agency.
            if (!isAdminOfAnyActiveTag(report.getId(), callerEmail)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Only a tagged agency's admins can update this report");
            }
        }
    }

    /** Work-order spawn / merge require the claiming agency (decision 3). */
    public boolean isClaimingAgencyAdmin(Post report, String callerEmail) {
        String claimingId = report.getClaimingAgencyGroupId();
        if (claimingId == null || claimingId.isBlank()) return false;
        return isAgencyAdmin(claimingId, callerEmail);
    }

    private void requireClaimingAgencyAdmin(Post report, String callerEmail) {
        String claimingId = report.getClaimingAgencyGroupId();
        if (claimingId == null || claimingId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Claim this report before scheduling or resolving it");
        }
        if (!isAgencyAdmin(claimingId, callerEmail)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the claiming agency can schedule or resolve this report");
        }
    }

    private boolean isAdminOfAnyActiveTag(Long postId, String email) {
        for (CivicReportAgency tag : tagRepo.findByPostIdAndActiveTrue(postId)) {
            if (isAgencyAdmin(tag.getAgencyGroupId(), email)) return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Queue folding (read)
    // ─────────────────────────────────────────────────────────────────────

    /** Distinct post ids an agency has an ACTIVE tag on — the queue's report set. */
    @Transactional(readOnly = true)
    public List<Long> reportIdsForAgency(String agencyGroupId) {
        return tagRepo.findByAgencyGroupIdAndActiveTrue(agencyGroupId).stream()
                .map(CivicReportAgency::getPostId).filter(id -> id != null)
                .distinct().collect(Collectors.toList());
    }

    /** Active tags per post (resolved names + claim state) for the queue fold. */
    @Transactional(readOnly = true)
    public Map<Long, List<AgencyTag>> activeTagsByPost(Collection<Long> postIds) {
        Map<Long, List<AgencyTag>> out = new HashMap<>();
        if (postIds == null || postIds.isEmpty()) return out;
        List<CivicReportAgency> rows = tagRepo.findByPostIdInAndActiveTrue(postIds);
        Set<String> agencyIds = rows.stream().map(CivicReportAgency::getAgencyGroupId)
                .filter(id -> id != null).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> names = new HashMap<>();
        for (Group g : groupRepo.findAllById(agencyIds)) {
            if (g != null && g.getGroupId() != null) names.put(g.getGroupId(), g.getGroupName());
        }
        for (CivicReportAgency r : rows) {
            out.computeIfAbsent(r.getPostId(), k -> new ArrayList<>())
                    .add(new AgencyTag(r.getAgencyGroupId(), names.get(r.getAgencyGroupId()),
                            r.isClaimed(), r.getTagSource()));
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private Post mustBeCivicReport(Long postId) {
        Post p = taskRepo.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found"));
        if (p.getCivicStatus() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a civic report");
        }
        return p;
    }

    private boolean isAuthorizedAgency(String groupId) {
        return groupRepo.findByGroupId(groupId).map(Group::isAgencyAuthorized).orElse(false);
    }

    private boolean isAgencyAdmin(String groupId, String email) {
        Group g = groupRepo.findByGroupId(groupId).orElse(null);
        if (g == null || email == null) return false;
        return GroupRole.fromGroup(g, email).isAtLeastAdmin();
    }
}
