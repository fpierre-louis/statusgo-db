package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.GroupRole;
import io.sitprep.sitprepapi.domain.AgencyAlert;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Post.PostPriority;
import io.sitprep.sitprepapi.domain.Post.PostStatus;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.AgencyAlertResultDto;
import io.sitprep.sitprepapi.dto.SendAgencyAlertRequest;
import io.sitprep.sitprepapi.repo.AgencyAlertRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.dao.DataIntegrityViolationException;
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
 * <p><b>v1 scope notes:</b> dispatch reuses {@code sendHazardAlertBatch}
 * (one batched multicast, still subject to the notification layer) and runs
 * within this transaction — fine for beta volume; move it off-transaction /
 * async when a single blast can exceed a few thousand recipients. Stale
 * recipients are bounded by a {@value #RECENCY_DAYS}-day last-seen window.</p>
 */
@Service
public class AgencyAlertService {

    private static final int RECENCY_DAYS = 30;
    private static final Set<String> TIERS = Set.of("emergency", "advisory", "notice");

    private final GroupRepo groupRepo;
    private final AgencyAlertRepo agencyAlertRepo;
    private final PostRepo postRepo;
    private final UserInfoRepo userInfoRepo;
    private final NotificationService notificationService;

    public AgencyAlertService(GroupRepo groupRepo,
                              AgencyAlertRepo agencyAlertRepo,
                              PostRepo postRepo,
                              UserInfoRepo userInfoRepo,
                              NotificationService notificationService) {
        this.groupRepo = groupRepo;
        this.agencyAlertRepo = agencyAlertRepo;
        this.postRepo = postRepo;
        this.userInfoRepo = userInfoRepo;
        this.notificationService = notificationService;
    }

    @Transactional
    public AgencyAlertResultDto send(String groupId, String callerEmail, SendAgencyAlertRequest req) {
        Group group = groupRepo.findByGroupId(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found"));

        // Authority — never trust the client. Caller must be owner/admin AND
        // the group must hold a claimed jurisdiction (set only by super-admin
        // provisioning). Having jurisdiction IS the agency-send authorization.
        if (!GroupRole.fromGroup(group, callerEmail).isAtLeastAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Group admin or owner role required");
        }
        Set<String> jurisdiction = new LinkedHashSet<>();
        if (group.getJurisdictionZips() != null) {
            for (String z : group.getJurisdictionZips()) {
                if (z != null && !z.isBlank()) jurisdiction.add(z.trim());
            }
        }
        if (jurisdiction.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This group has no claimed jurisdiction — not authorized to send agency alerts");
        }

        // Clamp the requested zips to the jurisdiction (can't blast zips you
        // don't own); empty request ⇒ the full jurisdiction.
        List<String> targetZips = new ArrayList<>();
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

        String title = trim(req == null ? null : req.title(), 200);
        String body = trim(req == null ? null : req.body(), 2000);
        if (title == null && body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Alert needs a title or a message");
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
                        targetZips, true);
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
        post.setLatitude(parseDouble(group.getLatitude()));
        post.setLongitude(parseDouble(group.getLongitude()));
        Post savedPost = postRepo.save(post);

        // Recipients — indexed zip lookup, bounded by recency (stale-location
        // guard). The notification layer drops tokenless users.
        Instant since = Instant.now().minus(RECENCY_DAYS, ChronoUnit.DAYS);
        List<UserInfo> recipients = userInfoRepo
                .findByLastKnownZipInAndLastKnownLocationAtAfter(targetZips, since);

        String pushTitle = title != null ? title : ("Alert from " + safe(group.getGroupName()));
        notificationService.sendHazardAlertBatch(
                recipients,
                pushTitle,
                body == null ? "" : body,
                "agency-alert:" + savedPost.getId(),
                "/community/posts/" + savedPost.getId()
        );

        alert.setPostId(savedPost.getId());
        alert.setRecipientCount(recipients.size());
        alert.setDispatchedAt(Instant.now());
        agencyAlertRepo.save(alert);

        return new AgencyAlertResultDto(alert.getId(), savedPost.getId(), recipients.size(), targetZips, false);
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

    private static Double parseDouble(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trim(String raw, int max) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isBlank()) return null;
        return v.length() <= max ? v : v.substring(0, max);
    }

    private static String safe(String s) {
        return s == null ? "your area" : s;
    }
}
