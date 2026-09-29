package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.MentionDto;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * @-mentions in community POSTS (Composer V2 C9f, owner Q6, 2026-09-28).
 *
 * <p>The comment-mention stack already existed (MentionToken + MentionService +
 * {@code post_mention} notices); posts reuse all of it. This class owns the two
 * things posts add: resolving a page of mention names in one query, and the
 * notification — with the guard every mention notice needs and comment
 * mentions were missing:</p>
 * <ul>
 *   <li>never the actor (mentioning yourself is not an event);</li>
 *   <li>never across a BLOCK, in either direction (a blocked person must not
 *       be able to ping someone who blocked them by typing their name);</li>
 *   <li>never someone who can't READ the post (a group-scoped or personal
 *       post must not leak a snippet to a stranger through a notification).</li>
 * </ul>
 */
@Service
public class PostMentionService {

    private static final Logger log = LoggerFactory.getLogger(PostMentionService.class);

    private final MentionService mentionService;
    private final NotificationService notificationService;
    private final UserInfoRepo userInfoRepo;
    private final BlockService blockService;
    private final PostReadAuthorizer readAuthorizer;

    public PostMentionService(MentionService mentionService,
                              NotificationService notificationService,
                              UserInfoRepo userInfoRepo,
                              BlockService blockService,
                              PostReadAuthorizer readAuthorizer) {
        this.mentionService = mentionService;
        this.notificationService = notificationService;
        this.userInfoRepo = userInfoRepo;
        this.blockService = blockService;
        this.readAuthorizer = readAuthorizer;
    }

    /** id → resolved entry, for every id across a page — ONE user lookup. */
    public Map<String, MentionDto> resolveAll(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        Map<String, MentionDto> out = new HashMap<>();
        for (MentionDto m : mentionService.resolve(ids)) {
            if (m.userId() != null) out.put(m.userId().toLowerCase(Locale.ROOT), m);
        }
        return out;
    }

    /** Plain text for places that must never show a raw token (previews). */
    public String toPlainText(String content) {
        return mentionService.toPlainText(content);
    }

    /** Only the ids an edit ADDED (so an edit doesn't re-ping everyone). */
    public List<String> newlyMentioned(String previous, String updated) {
        return mentionService.newlyMentioned(previous, updated);
    }

    /**
     * The guard for any mention notice about {@code post}: not the actor, no
     * block either way, and the recipient must be able to read the post.
     */
    public boolean shouldNotify(Post post, String actorEmail, String recipientEmail) {
        if (recipientEmail == null || recipientEmail.isBlank()) return false;
        if (actorEmail != null && actorEmail.equalsIgnoreCase(recipientEmail)) return false;
        if (actorEmail != null && blockService.isAnyBlock(actorEmail, recipientEmail)) return false;
        return post != null && readAuthorizer.canRead(post, recipientEmail);
    }

    /**
     * Notify the accounts {@code userIds} names that they were mentioned in
     * {@code post} by {@code actorEmail}. Call AFTER COMMIT. Best-effort per
     * recipient: one failed delivery never stops the rest.
     */
    public void notifyPostMentioned(Post post, String actorEmail, List<String> userIds) {
        if (post == null || userIds == null || userIds.isEmpty()) return;
        UserInfo actor = actorEmail == null ? null : userInfoRepo.findByUserEmailIgnoreCase(actorEmail).orElse(null);
        String actorName = actor != null && actor.getUserFirstName() != null && !actor.getUserFirstName().isBlank()
                ? actor.getUserFirstName() : "Someone";
        String actorImage = actor != null ? actor.getProfileImageUrl() : null;
        String actorUserId = actor != null ? actor.getId() : null;
        // Resolved before truncation: a push body must never carry a raw
        // token, and slicing one in half would leave an unresolvable fragment.
        String body = actorName + " mentioned you in a post: " + snippet(toPlainText(post.getDescription()), 80);
        String title = post.getTitle() != null && !post.getTitle().isBlank() ? snippet(post.getTitle(), 60) : "A community post";
        String targetUrl = "/community/posts/" + post.getId();

        for (String email : mentionService.emailsFor(userIds)) {
            try {
                if (!shouldNotify(post, actorEmail, email)) continue;
                userInfoRepo.findByUserEmailIgnoreCase(email).ifPresent(u ->
                        notificationService.deliverPresenceAware(
                                u.getUserEmail(), title, body, actorName, actorImage,
                                NotificationService.TYPE_POST_MENTION,
                                String.valueOf(post.getId()), targetUrl, null,
                                u.getFcmtoken(), actorUserId));
            } catch (Exception e) {
                log.warn("post mention notice failed post={} recipient={}: {}", post.getId(), email, e.getMessage());
            }
        }
    }

    private static String snippet(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
