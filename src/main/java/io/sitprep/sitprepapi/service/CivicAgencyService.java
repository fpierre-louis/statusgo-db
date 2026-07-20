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
import java.util.Objects;
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

    /** Merge outcome — the survivor + the ids that were merged into it. */
    public record MergeResult(Long canonicalId, List<Long> mergedDuplicateIds, int mergedDuplicateCount) {}

    /** Unmerge outcome — the restored report + the canonical it left. */
    public record UnmergeResult(Long postId, Long formerCanonicalId) {}

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
    // Merge / unmerge (Slice 3)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Merge N duplicate civic reports INTO a canonical (locked decisions 1–8).
     * The caller must be an admin of the CLAIMING agency of the canonical
     * (decision 6; the resource has already checked agency-authorized + admin).
     *
     * <p>Per duplicate: re-point its child work orders' {@code sourcePostId} to
     * the canonical (4a — work follows the survivor); UNION its active agency
     * tags onto the canonical as {@code tag_source='merged'} where the canonical
     * isn't already actively tagged (4b — claim state does NOT transfer); FLATTEN
     * any grand-duplicates (rows already merged into this duplicate) directly
     * onto the canonical so chains never nest (decision 3). Media stays on the
     * duplicates (4c); filers stay on their own rows (4e); confirms aggregate via
     * read-through, not a stored rollup (4d).</p>
     *
     * <p>Guards: merging INTO an already-merged report → 409 (decision 3); a
     * duplicate claimed by ANOTHER agency → 409 "release it first" (decision 7);
     * a duplicate already merged elsewhere → 409. No category/jurisdiction
     * restriction (decision 8). Self-id is filtered out (the DB CHECK is the
     * belt-and-suspenders floor).</p>
     */
    @Transactional
    public MergeResult merge(Long canonicalId, List<Long> duplicateIds, String agencyGroupId, String callerEmail) {
        if (canonicalId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No canonical report supplied");
        }
        Post canonical = mustBeCivicReport(canonicalId);
        // Decision 3 — cannot merge INTO an already-merged (duplicate) report.
        if (canonical.getMergedIntoPostId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That report is itself merged into another — merge into the canonical instead");
        }
        // Decision 6 — the claiming agency of the canonical (admin) only.
        requireMergeAuthority(canonical, agencyGroupId, callerEmail);

        List<Long> requested = (duplicateIds == null ? List.<Long>of() : duplicateIds).stream()
                .filter(Objects::nonNull)
                .filter(id -> !id.equals(canonicalId)) // never self-merge (CHECK is the floor)
                .distinct()
                .collect(Collectors.toList());
        if (requested.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No duplicate reports supplied");
        }

        String actor = callerEmail == null ? null : callerEmail.trim().toLowerCase();
        Instant now = Instant.now();
        List<Long> merged = new ArrayList<>();

        for (Long dupId : requested) {
            Post dup = mustBeCivicReport(dupId);
            if (dup.getMergedIntoPostId() != null) {
                if (dup.getMergedIntoPostId().equals(canonicalId)) continue; // idempotent — already merged here
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A selected report is already merged into a different report");
            }
            // Decision 7 — a duplicate claimed by ANOTHER agency blocks the merge.
            String dupClaim = dup.getClaimingAgencyGroupId();
            if (dupClaim != null && !dupClaim.isBlank() && !dupClaim.equalsIgnoreCase(agencyGroupId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A selected report is claimed by another agency; release it first");
            }

            // Decision 3 FLATTEN — re-point this duplicate's own duplicates
            // (grand-duplicates) DIRECTLY at the canonical so no chain forms.
            for (Post grand : taskRepo.findByMergedIntoPostId(dupId)) {
                grand.setMergedIntoPostId(canonicalId);
                grand.setMergedAt(now);
                grand.setMergedByEmail(actor);
                taskRepo.save(grand);
            }
            // Decision 4a — work follows the survivor. Re-point work orders whose
            // sourcePostId is this duplicate (grand-duplicates' work orders were
            // already re-pointed to this duplicate on their own merge, so this
            // one sweep catches them too).
            for (Post wo : taskRepo.findBySourcePostId(dupId)) {
                wo.setSourcePostId(canonicalId);
                taskRepo.save(wo);
            }
            // Decision 4b — UNION active agency tags onto the canonical.
            unionActiveTagsOntoCanonical(dupId, canonicalId);

            dup.setMergedIntoPostId(canonicalId);
            dup.setMergedAt(now);
            dup.setMergedByEmail(actor);
            taskRepo.save(dup);
            merged.add(dupId);
        }
        return new MergeResult(canonicalId, merged, merged.size());
    }

    /**
     * Restore a merged duplicate to a standalone report (decision 9 — unmerge is
     * in Slice 3; humans mis-merge). LOCKED: re-pointed work orders STAY with the
     * former canonical (no unwind of sourcePostId), and union'd
     * {@code tag_source='merged'} tags are LEFT on the canonical (they can't be
     * attributed to a single duplicate — several duplicates may have contributed
     * the same agency, and other duplicates may still be merged). Same claim-gate
     * as merge (admin of the former canonical's claiming agency).
     */
    @Transactional
    public UnmergeResult unmerge(Long duplicateId, String agencyGroupId, String callerEmail) {
        Post dup = mustBeCivicReport(duplicateId);
        Long canonicalId = dup.getMergedIntoPostId();
        if (canonicalId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Report is not merged");
        }
        Post canonical = taskRepo.findById(canonicalId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Canonical report not found"));
        requireMergeAuthority(canonical, agencyGroupId, callerEmail);

        dup.setMergedIntoPostId(null);
        dup.setMergedAt(null);
        dup.setMergedByEmail(null);
        taskRepo.save(dup);
        // Work orders + tag_source='merged' tags intentionally left in place (see javadoc).
        return new UnmergeResult(duplicateId, canonicalId);
    }

    /**
     * Decision 6 authority — the caller must be an admin of the agency that holds
     * the active CLAIM on the canonical. (The resource layer already enforced
     * "admin of an agencyAuthorized group"; this adds the claiming-agency match.)
     */
    private void requireMergeAuthority(Post canonical, String agencyGroupId, String callerEmail) {
        String claimingId = canonical.getClaimingAgencyGroupId();
        if (claimingId == null || claimingId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Claim this report before merging duplicates into it");
        }
        if (agencyGroupId == null || !claimingId.equalsIgnoreCase(agencyGroupId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the claiming agency can merge or unmerge this report");
        }
        if (!isAgencyAdmin(claimingId, callerEmail)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the claiming agency's admins can merge or unmerge");
        }
    }

    /** Decision 4b — union a duplicate's active tags onto the canonical as 'merged'. */
    private void unionActiveTagsOntoCanonical(Long dupId, Long canonicalId) {
        Set<String> canonActive = tagRepo.findByPostIdAndActiveTrue(canonicalId).stream()
                .map(CivicReportAgency::getAgencyGroupId)
                .filter(Objects::nonNull).map(s -> s.toLowerCase())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (CivicReportAgency tag : tagRepo.findByPostIdAndActiveTrue(dupId)) {
            String aid = tag.getAgencyGroupId();
            if (aid == null || canonActive.contains(aid.toLowerCase())) continue; // already active → keep its source
            // New (or re-activated tombstone) tag on the canonical; provenance
            // preserved as 'merged'. Claim state NOT copied (upsertTag never
            // touches claimed).
            upsertTag(canonicalId, aid, "merged", true);
        }
    }

    /**
     * Read-through fold (decision 4d/queue): the duplicate ids grouped by their
     * canonical, for {@code mergedDuplicateCount}/{@code mergedDuplicateIds} and
     * the confirm-count aggregation. One batched query.
     */
    @Transactional(readOnly = true)
    public Map<Long, List<Long>> duplicateIdsByCanonical(Collection<Long> canonicalIds) {
        Map<Long, List<Long>> out = new HashMap<>();
        if (canonicalIds == null || canonicalIds.isEmpty()) return out;
        for (Post d : taskRepo.findByMergedIntoPostIdIn(canonicalIds)) {
            Long c = d.getMergedIntoPostId();
            if (c != null && d.getId() != null) {
                out.computeIfAbsent(c, k -> new ArrayList<>()).add(d.getId());
            }
        }
        return out;
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
