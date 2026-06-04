package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.GroupPost;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.PublisherPublishAudit;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PublisherPublishAuditRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

@Service
public class PublisherPublishAuditService {

    private final PublisherPublishAuditRepo auditRepo;
    private final UserInfoRepo userInfoRepo;
    private final GroupRepo groupRepo;

    public PublisherPublishAuditService(PublisherPublishAuditRepo auditRepo,
                                        UserInfoRepo userInfoRepo,
                                        GroupRepo groupRepo) {
        this.auditRepo = auditRepo;
        this.userInfoRepo = userInfoRepo;
        this.groupRepo = groupRepo;
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
