package io.sitprep.sitprepapi.websocket;

import io.sitprep.sitprepapi.domain.GroupPost;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.user.SimpSubscription;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drops live socket subscriptions to one group's topics (BE-13, B5 2026-10-14).
 *
 * <p>{@link WebSocketTopicAuthorizer} decides at SUBSCRIBE time, and only then.
 * A member removed from a group (or one who left) kept every subscription
 * their open page had made — the group's chat, its roster frames with emails,
 * member status and member LOCATION — until the socket happened to reconnect.
 * This closes that window from the server side: after the REMOVE commits and
 * its frame has gone out, each of that account's subscriptions to the group's
 * topics is unsubscribed in the broker, so nothing more is delivered to it.</p>
 *
 * <p>How: Spring's {@link SimpUserRegistry} already tracks every session's
 * subscriptions per user (the principal is the account's email, set at
 * CONNECT by {@link WebSocketAuthChannelInterceptor}). For each matching one,
 * an UNSUBSCRIBE carrying that session and subscription id is sent on the
 * broker channel, which the simple broker handles exactly like the client's
 * own UNSUBSCRIBE. The client is not told; its page drops its streams on the
 * REMOVE frame (the FE half, F2), and a re-join re-subscribes on the next
 * page load.</p>
 *
 * <p>A post's comment thread ({@code /topic/group-post-comments/{postId}}) is
 * keyed by the post, not the group, so it is matched by looking the post up:
 * an open thread on one of the group's posts stops too.</p>
 *
 * <p>Single-process by design, like the simple broker itself: subscriptions
 * live in this dyno's memory, so this is the complete set.</p>
 *
 * <p>Lazy providers, never direct dependencies: the broker channel and the
 * registry are built by the broker configuration, which (through the
 * interceptor) reaches the services that call this class.</p>
 */
@Component
public class GroupTopicEvictor {

    private static final Logger log = LoggerFactory.getLogger(GroupTopicEvictor.class);

    /** The topics keyed by a group (or household) id; mirrors the authorizer's group rules. */
    private static final Pattern GROUP_TOPIC = Pattern.compile(
            "^/topic/(?:group-posts/([^/]+?)(?:/delete|/typing)?"
                    + "|group/([^/]+)/.+"
                    + "|households/([^/]+)/.+)$");

    /** A group post's comment thread; keyed by the post id (the authorizer's rule). */
    private static final Pattern POST_COMMENT_TOPIC =
            Pattern.compile("^/topic/group-post-comments/(\\d+)(?:/delete)?$");

    private final ObjectProvider<SimpUserRegistry> userRegistry;
    private final ObjectProvider<MessageChannel> brokerChannel;
    private final ObjectProvider<GroupPostRepo> postRepo;

    public GroupTopicEvictor(ObjectProvider<SimpUserRegistry> userRegistry,
                             @Qualifier("brokerChannel") ObjectProvider<MessageChannel> brokerChannel,
                             ObjectProvider<GroupPostRepo> postRepo) {
        this.userRegistry = userRegistry;
        this.brokerChannel = brokerChannel;
        this.postRepo = postRepo;
    }

    /** Drop {@code email}'s subscriptions to {@code groupId}'s topics. Returns how many. Never throws. */
    public int evict(String groupId, String email) {
        if (groupId == null || groupId.isBlank() || email == null || email.isBlank()) return 0;
        String who = email.trim().toLowerCase(Locale.ROOT);
        return run(groupId, sub -> {
            SimpUser user = sub.getSession() == null ? null : sub.getSession().getUser();
            return user != null && user.getName() != null && user.getName().trim().equalsIgnoreCase(who);
        });
    }

    /** Drop every account's subscriptions to {@code groupId}'s topics (the group was deleted). */
    public int evictAll(String groupId) {
        if (groupId == null || groupId.isBlank()) return 0;
        return run(groupId, sub -> true);
    }

    /** The group id a destination belongs to, or null when it isn't a group topic. */
    static String groupIdOf(String destination) {
        if (destination == null) return null;
        Matcher m = GROUP_TOPIC.matcher(destination);
        if (!m.matches()) return null;
        for (int i = 1; i <= m.groupCount(); i++) {
            if (m.group(i) != null) return m.group(i);
        }
        return null;
    }

    /** The post id of a group post's comment-thread topic, or null. */
    static Long postIdOf(String destination) {
        if (destination == null) return null;
        Matcher m = POST_COMMENT_TOPIC.matcher(destination);
        if (!m.matches()) return null;
        try {
            return Long.valueOf(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int run(String groupId, java.util.function.Predicate<SimpSubscription> whose) {
        try {
            SimpUserRegistry registry = userRegistry.getIfAvailable();
            MessageChannel channel = brokerChannel.getIfAvailable();
            if (registry == null || channel == null) return 0;
            Set<SimpSubscription> subs = registry.findSubscriptions(
                    sub -> whose.test(sub) && (groupId.equals(groupIdOf(sub.getDestination()))
                            || postIdOf(sub.getDestination()) != null));
            Set<Long> threadsOfThisGroup = postsOfGroup(groupId, subs);
            int dropped = 0;
            for (SimpSubscription sub : subs) {
                if (sub.getSession() == null) continue;
                Long postId = postIdOf(sub.getDestination());
                if (postId != null && !threadsOfThisGroup.contains(postId)) continue;
                channel.send(unsubscribe(sub.getSession().getId(), sub.getId(), sub.getDestination()));
                dropped++;
            }
            if (dropped > 0) {
                log.info("GroupTopicEvictor: dropped {} subscription(s) to group {}", dropped, groupId);
            }
            return dropped;
        } catch (RuntimeException e) {
            // Never fail the write that triggered this; the next reconnect re-checks anyway.
            log.warn("GroupTopicEvictor: could not drop subscriptions to group {}: {}", groupId, e.getMessage());
            return 0;
        }
    }

    /** Of the comment threads in {@code subs}, the ids of posts that belong to {@code groupId}. One query. */
    private Set<Long> postsOfGroup(String groupId, Set<SimpSubscription> subs) {
        Set<Long> ids = new HashSet<>();
        for (SimpSubscription sub : subs) {
            Long id = postIdOf(sub.getDestination());
            if (id != null) ids.add(id);
        }
        Set<Long> ofGroup = new HashSet<>();
        if (ids.isEmpty()) return ofGroup;
        GroupPostRepo repo = postRepo.getIfAvailable();
        if (repo == null) return ofGroup;
        for (GroupPost p : repo.findAllById(ids)) {
            if (p != null && groupId.equals(p.getGroupId())) ofGroup.add(p.getId());
        }
        return ofGroup;
    }

    static Message<byte[]> unsubscribe(String sessionId, String subscriptionId, String destination) {
        SimpMessageHeaderAccessor h = SimpMessageHeaderAccessor.create(SimpMessageType.UNSUBSCRIBE);
        h.setSessionId(sessionId);
        h.setSubscriptionId(subscriptionId);
        h.setDestination(destination);
        return MessageBuilder.createMessage(new byte[0], h.getMessageHeaders());
    }
}
