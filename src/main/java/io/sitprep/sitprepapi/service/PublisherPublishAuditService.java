package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.GroupPost;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.PublisherPublishAudit;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.PublisherPublishAuditDto;
import io.sitprep.sitprepapi.dto.ReviewPublisherPublishAuditRequest;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PublisherPublishAuditRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class PublisherPublishAuditService {

    private final PublisherPublishAuditRepo auditRepo;
    private final UserInfoRepo userInfoRepo;
    private final GroupRepo groupRepo;
    private final PublisherPublishRateLimiter rateLimiter;
    private final AdminAuditLogService adminAuditLogService;

    public PublisherPublishAuditService(PublisherPublishAuditRepo auditRepo,
                                        UserInfoRepo userInfoRepo,
                                        GroupRepo groupRepo,
                                        PublisherPublishRateLimiter rateLimiter,
                                        AdminAuditLogService adminAuditLogService) {
        this.auditRepo = auditRepo;
        this.userInfoRepo = userInfoRepo;
        this.groupRepo = groupRepo;
        this.rateLimiter = rateLimiter;
        this.adminAuditLogService = adminAuditLogService;
    }

    public void requirePublisherPostAllowed(String groupId,
                                            String authorEmail,
                                            String actorEmail,
                                            boolean sponsored) {
        ResolvedPublisher publisher = resolvePublisher(groupId, authorEmail, actorEmail);
        if (!sponsored && publisher.user() == null) return;
        String key = publisherKey(publisher, actorEmail);
        if (!rateLimiter.tryConsume(key, sponsored)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    sponsored
                            ? "Sponsored posting limit reached for this publisher"
                            : "Official posting limit reached for this publisher");
        }
    }

    @Transactional
    public void recordCommunityPost(Post post, String actorEmail) {
        if (post == null || post.getId() == null) return;
        ResolvedPublisher publisher = resolvePublisher(
                post.getAuthoredAsGroupId(), post.getRequesterEmail(), actorEmail);
        if (publisher.user() == null) return;

        save("OFFICIAL_COMMUNITY_POST",
                actorEmail,
                publisher,
                "task",
                post.getId(),
                post.getLatitude(),
                post.getLongitude(),
                message(post.getTitle(), post.getDescription()));
    }

    @Transactional
    public void recordSponsoredPost(Post post, String actorEmail) {
        if (post == null || post.getId() == null || !post.isSponsored()) return;
        ResolvedPublisher publisher = resolvePublisher(
                post.getAuthoredAsGroupId(), post.getRequesterEmail(), actorEmail);
        save("SPONSORED_POST",
                actorEmail,
                publisher,
                "task",
                post.getId(),
                post.getLatitude(),
                post.getLongitude(),
                message(post.getTitle(), post.getDescription()));
    }

    @Transactional
    public void recordGroupPost(GroupPost post, String actorEmail) {
        if (post == null || post.getId() == null) return;
        ResolvedPublisher publisher = resolvePublisher(post.getGroupId(), post.getAuthor(), actorEmail);
        if (publisher.user() == null) return;

        save("OFFICIAL_GROUP_POST",
                actorEmail,
                publisher,
                "post",
                post.getId(),
                null,
                null,
                post.getContent());
    }

    @Transactional(readOnly = true)
    public List<PublisherPublishAuditDto> listReviews(String rawStatus) {
        PublisherPublishAudit.ReviewStatus status = parseReviewStatus(rawStatus, true);
        List<PublisherPublishAudit> rows = status == null
                ? auditRepo.findTop100ByOrderByCreatedAtDesc()
                : auditRepo.findByReviewStatusOrderByCreatedAtDesc(status);
        return rows.stream().map(PublisherPublishAuditDto::from).toList();
    }

    @Transactional
    public PublisherPublishAuditDto review(Long id,
                                           ReviewPublisherPublishAuditRequest req,
                                           String reviewerEmail) {
        PublisherPublishAudit row = auditRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Publisher review row not found"));
        PublisherPublishAudit.ReviewStatus status =
                parseReviewStatus(req == null ? null : req.status(), false);
        PublisherPublishAudit.ReviewStatus previous = row.getReviewStatus();
        row.setReviewStatus(status);
        row.setReviewerEmail(normalize(reviewerEmail));
        row.setReviewerNotes(trim(req == null ? null : req.reviewerNotes(), 1000));
        row.setReviewedAt(Instant.now());
        PublisherPublishAudit saved = auditRepo.save(row);
        adminAuditLogService.record(
                reviewerEmail,
                "REVIEWED_PUBLISHER_POST",
                "publisher-review",
                String.valueOf(saved.getId()),
                "status " + previous + " -> " + status + "; post="
                        + saved.getPostTable() + "#" + saved.getPostId());
        return PublisherPublishAuditDto.from(saved);
    }

    private void save(String eventType,
                      String actorEmail,
                      ResolvedPublisher publisher,
                      String postTable,
                      Long postId,
                      Double latitude,
                      Double longitude,
                      String message) {
        PublisherPublishAudit audit = new PublisherPublishAudit();
        audit.setEventType(eventType);
        audit.setActorEmail(normalize(actorEmail));
        audit.setPublisherEmail(publisher.user() == null
                ? normalize(actorEmail)
                : normalize(publisher.user().getUserEmail()));
        audit.setOrganizationId(publisher.group() == null ? null : publisher.group().getGroupId());
        audit.setOrganizationName(publisher.group() == null ? null : publisher.group().getGroupName());
        audit.setOrganizationKind(organizationKind(publisher));
        audit.setReachLabel(reachLabel(publisher));
        audit.setPermanentAddress(publisher.user() == null
                ? null
                : trim(publisher.user().getVerifiedPublisherPermanentAddress(), 400));
        audit.setTemporaryEventAddress(publisher.user() == null
                ? null
                : trim(publisher.user().getVerifiedPublisherTemporaryEventAddress(), 400));
        audit.setLatitude(latitude);
        audit.setLongitude(longitude);
        audit.setPostTable(postTable);
        audit.setPostId(postId);
        audit.setMessage(trim(message, 4096));
        auditRepo.save(audit);
    }

    private static String publisherKey(ResolvedPublisher publisher, String actorEmail) {
        if (publisher.group() != null && publisher.group().getGroupId() != null) {
            return "group:" + publisher.group().getGroupId();
        }
        if (publisher.user() != null && publisher.user().getUserEmail() != null) {
            return "publisher:" + publisher.user().getUserEmail();
        }
        return "actor:" + actorEmail;
    }

    private static PublisherPublishAudit.ReviewStatus parseReviewStatus(String raw, boolean allowAll) {
        if (raw == null || raw.isBlank()) return allowAll ? PublisherPublishAudit.ReviewStatus.PENDING : null;
        String value = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (allowAll && "ALL".equals(value)) return null;
        try {
            return PublisherPublishAudit.ReviewStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "status must be PENDING, APPROVED, REJECTED, NEEDS_INFO, or ALL");
        }
    }

    private ResolvedPublisher resolvePublisher(String groupId, String authorEmail, String actorEmail) {
        Group group = null;
        if (groupId != null && !groupId.isBlank()) {
            group = groupRepo.findByGroupId(groupId.trim()).orElse(null);
        }

        UserInfo user = null;
        if (group != null) {
            user = userInfoRepo.findFirstByVerifiedPublisherGroupIdIgnoreCase(group.getGroupId())
                    .filter(UserInfo::isVerifiedPublisher)
                    .orElse(null);
            if (user == null) user = verifiedUser(group.getOwnerEmail()).orElse(null);
        }
        if (user == null) user = verifiedUser(authorEmail).orElse(null);
        if (user == null) user = verifiedUser(actorEmail).orElse(null);
        return new ResolvedPublisher(user, group);
    }

    private Optional<UserInfo> verifiedUser(String email) {
        if (email == null || email.isBlank()) return Optional.empty();
        return userInfoRepo.findByUserEmailIgnoreCase(email.trim()).filter(UserInfo::isVerifiedPublisher);
    }

    private static String organizationKind(ResolvedPublisher publisher) {
        if (publisher.user() != null && publisher.user().getVerifiedPublisherKind() != null) {
            return trim(publisher.user().getVerifiedPublisherKind(), 80);
        }
        return publisher.group() == null ? null : trim(publisher.group().getGroupType(), 80);
    }

    private static String reachLabel(ResolvedPublisher publisher) {
        if (publisher.user() != null && publisher.user().getVerifiedPublisherServiceArea() != null) {
            return trim(publisher.user().getVerifiedPublisherServiceArea(), 400);
        }
        Group group = publisher.group();
        if (group == null) return null;
        StringBuilder out = new StringBuilder();
        append(out, group.getAddress());
        append(out, group.getZipCode());
        return out.isEmpty() ? null : trim(out.toString(), 400);
    }

    private static String message(String title, String body) {
        String t = trim(title, 200);
        String b = trim(body, 4096);
        if (t == null) return b;
        if (b == null) return t;
        return t + "\n" + b;
    }

    private static void append(StringBuilder out, String value) {
        String trimmed = trim(value, 200);
        if (trimmed == null) return;
        if (!out.isEmpty()) out.append(" · ");
        out.append(trimmed);
    }

    private static String normalize(String value) {
        String trimmed = trim(value, 255);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static String trim(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private record ResolvedPublisher(UserInfo user, Group group) {}
}
