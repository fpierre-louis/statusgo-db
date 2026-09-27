package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.AgencyAlert;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Post.PostPriority;
import io.sitprep.sitprepapi.domain.Post.PostStatus;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.AgencyAlertResultDto;
import io.sitprep.sitprepapi.dto.AgencyAlertStatusDto;
import io.sitprep.sitprepapi.dto.SendAgencyAlertRequest;
import io.sitprep.sitprepapi.repo.AgencyAlertRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Sends a verified agency's geo-targeted alert to everyone whose CURRENT zip
 * falls in the agency's claimed jurisdiction — regardless of membership
 * (Phase 5 Slice D). Honors the §5 risk list: server-side authority + zip
 * clamping, an idempotency guard against double-sends, and recipients found
 * by an indexed zip lookup (Slice C) rather than a Haversine scan.
 *
 * <p>Submission commits an official feed post, recipient snapshot, and queued
 * dispatch record atomically. {@link AgencyAlertDispatchService} performs the
 * provider call after commit. Stale recipients are bounded by a
 * {@value #RECENCY_DAYS}-day last-seen window.</p>
 */
@Service
public class AgencyAlertService {

    private static final int RECENCY_DAYS = 30;
    private static final Set<String> TIERS = Set.of("emergency", "advisory", "notice");

    private final GroupRepo groupRepo;
    private final AgencyAlertRepo agencyAlertRepo;
    private final PostRepo postRepo;
    private final AgencyAuthorizationService agencyAuthorizationService;

    public AgencyAlertService(GroupRepo groupRepo,
                              AgencyAlertRepo agencyAlertRepo,
                              PostRepo postRepo,
                              AgencyAuthorizationService agencyAuthorizationService) {
        this.groupRepo = groupRepo;
        this.agencyAlertRepo = agencyAlertRepo;
        this.postRepo = postRepo;
        this.agencyAuthorizationService = agencyAuthorizationService;
    }

    @Transactional
    public AgencyAlertResultDto send(String groupId, String callerEmail, SendAgencyAlertRequest req) {
        Group group = groupRepo.findByGroupId(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found"));

        agencyAuthorizationService.requireAgencyPostingAllowed(group, callerEmail);

        Set<String> jurisdiction = new LinkedHashSet<>();
        if (group.getJurisdictionZips() != null) {
            for (String z : group.getJurisdictionZips()) {
                if (z != null && !z.isBlank()) jurisdiction.add(z.trim());
            }
        }

        // Radius-authorized agencies ignore the legacy zip selector. Zip-only
        // agencies keep the old clamping behavior.
        List<String> targetZips = new ArrayList<>();
        if (!agencyAuthorizationService.hasGeo(group)) {
            List<String> requested = (req == null || req.affectedZips() == null) ? List.of() : req.affectedZips();
            if (requested.isEmpty()) {
                targetZips.addAll(jurisdiction);
            } else {
                Set<String> seen = new LinkedHashSet<>();
                for (String z : requested) {
                    String t = z == null ? "" : z.trim();
                    if (jurisdiction.contains(t) && seen.add(t)) targetZips.add(t);
                }
                if (targetZips.isEmpty()) targetZips.addAll(jurisdiction);
            }
        }

        String title = trim(req == null ? null : req.title(), 200);
        String body = trim(req == null ? null : req.body(), 2000);
        if (title == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Alert title is required");
        }
        String tier = normalizeTier(req == null ? null : req.officialTier());

        // Idempotency reserve — the double-send guard. Prefer the client key;
        // else content + a 10-minute window. Collision ⇒ return the prior
        // result WITHOUT re-dispatching (a duplicate city blast is the worst
        // possible failure here).
        String dedupKey = buildDedupKey(groupId, req == null ? null : req.idempotencyKey(), title, body);
        AgencyAlert alert = new AgencyAlert();
        alert.setPublisherGroupId(groupId);
        alert.setDedupKey(dedupKey);
        alert.setTitle(title);
        alert.setBody(body);
        alert.setOfficialTier(tier);
        alert.setAffectedZips(String.join(",", targetZips));
        alert.setCreatedBy(callerEmail);
        try {
            alert = agencyAlertRepo.saveAndFlush(alert);
        } catch (DataIntegrityViolationException dup) {
            AgencyAlert existing = agencyAlertRepo.findByDedupKey(dedupKey).orElse(null);
            if (existing != null) {
                return new AgencyAlertResultDto(existing.getId(), existing.getPostId(),
                        existing.getRecipientCount() == null ? 0 : existing.getRecipientCount(),
                        targetZips, true,
                        statusOf(existing).name(),
                        existing.getDeliveredCount(),
                        existing.getFailedCount());
            }
            throw dup;
        }

        // The official feed post (the durable record of the alert).
        Post post = new Post();
        post.setRequesterEmail(callerEmail);
        post.setKind("official");
        post.setOfficialTier(tier);
        post.setAuthoredAsGroupId(groupId);
        post.setTitle(title);
        post.setDescription(body == null ? "" : body);
        post.setStatus(PostStatus.OPEN);
        post.setPriority(PostPriority.URGENT);
        // CAPTURED, NOT DERIVED (V60). A dispatched NWS alert carries its own
        // end time; a human-composed one does not — a city writing "boil order
        // until Thursday" has no upstream feed to read it from, so the composer
        // states it. Null stays valid and renders as "until further notice",
        // which is the honest reading of an advisory with no stated end.
        post.setEffectiveUntil(req == null ? null : req.effectiveUntil());
        post.setLatitude(group.getJurisdictionLat() == null ? group.getLatitude() : group.getJurisdictionLat());
        post.setLongitude(group.getJurisdictionLng() == null ? group.getLongitude() : group.getJurisdictionLng());
        Post savedPost = postRepo.save(post);

        // Recipients — radius when provisioned, legacy zip lookup otherwise.
        Instant since = Instant.now().minus(RECENCY_DAYS, ChronoUnit.DAYS);
        List<UserInfo> recipients = agencyAuthorizationService.recipients(group, since);
        List<String> recipientSnapshot = recipients.stream()
                .map(UserInfo::getUserEmail)
                .filter(email -> email != null && !email.isBlank())
                .map(email -> email.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .sorted()
                .toList();

        alert.setPostId(savedPost.getId());
        alert.setRecipientCount(recipientSnapshot.size());
        alert.setRecipientEmails(recipientSnapshot);
        alert.setDispatchStatus(AgencyAlert.DispatchStatus.QUEUED);
        alert.setQueuedAt(Instant.now());
        alert.setNextAttemptAt(alert.getQueuedAt());
        agencyAlertRepo.save(alert);

        return new AgencyAlertResultDto(alert.getId(), savedPost.getId(), recipientSnapshot.size(), targetZips, false,
                alert.getDispatchStatus().name(), 0, 0);
    }

    @Transactional(readOnly = true)
    public List<AgencyAlertStatusDto> list(String groupId, String callerEmail, int limit) {
        Group group = groupRepo.findByGroupId(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found"));
        agencyAuthorizationService.requireAgencyAdmin(group, callerEmail);
        int bounded = Math.max(1, Math.min(limit, 50));
        return agencyAlertRepo.findByPublisherGroupIdOrderByCreatedAtDesc(
                        groupId, PageRequest.of(0, bounded)).stream()
                .map(AgencyAlertStatusDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AgencyAlertStatusDto get(String groupId, Long alertId, String callerEmail) {
        Group group = groupRepo.findByGroupId(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found"));
        agencyAuthorizationService.requireAgencyAdmin(group, callerEmail);
        AgencyAlert alert = agencyAlertRepo.findById(alertId)
                .filter(candidate -> groupId.equals(candidate.getPublisherGroupId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert not found"));
        return AgencyAlertStatusDto.from(alert);
    }

    private static String buildDedupKey(String groupId, String clientKey, String title, String body) {
        if (clientKey != null && !clientKey.isBlank()) {
            return groupId + ":k:" + clientKey.trim();
        }
        long window = Instant.now().toEpochMilli() / (10L * 60L * 1000L); // 10-min bucket
        int hash = ((title == null ? "" : title) + "|" + (body == null ? "" : body)).hashCode();
        return groupId + ":a:" + window + ":" + Integer.toHexString(hash);
    }

    private static String normalizeTier(String raw) {
        if (raw == null) return "advisory";
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return TIERS.contains(v) ? v : "advisory";
    }

    private static AgencyAlert.DispatchStatus statusOf(AgencyAlert alert) {
        return alert.getDispatchStatus() == null
                ? AgencyAlert.DispatchStatus.QUEUED
                : alert.getDispatchStatus();
    }


    private static String trim(String raw, int max) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isBlank()) return null;
        return v.length() <= max ? v : v.substring(0, max);
    }

}
