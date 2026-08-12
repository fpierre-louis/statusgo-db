package io.sitprep.sitprepapi.websocket;

import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.service.GroupPostService;
import io.sitprep.sitprepapi.service.PlanActivationService;
import io.sitprep.sitprepapi.service.PostReadAuthorizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single answer to "may this session subscribe to this destination?".
 *
 * <p>SUBSCRIBE authorization was added in 2026-07-07 for exactly one
 * destination — {@code /topic/activations/{id}/acks} — with the rationale
 * recorded on the interceptor: CONNECT-only auth "left every broker topic
 * open to any authenticated session". That reasoning was never applied to
 * the other topics, so live household chat, group boards, member
 * coordinates, DMs, and notification inboxes all remained subscribable by
 * any authenticated account that knew a group id or an email. Gating the
 * REST reads without this closes the door and leaves the window: the same
 * rows stream over the socket.</p>
 *
 * <p><b>Default deny.</b> A destination that matches no rule below is
 * rejected. This is the property that matters most — the previous design
 * was default-allow, which is why one guarded topic sat beside a dozen open
 * ones and why every topic added since 2026-07-07 was open on arrival. A
 * new topic now fails closed and its author must classify it here.</p>
 *
 * <p>Separate from {@link WebSocketAuthChannelInterceptor} so the rules are
 * unit-testable without STOMP plumbing.</p>
 */
@Component
public class WebSocketTopicAuthorizer {

    /**
     * Community feed frames. Deliberately public to any authenticated
     * session: this is the neighborhood stream, the same content
     * {@code GET /api/community/posts} serves to every signed-in user.
     * Personal tasks cannot appear here — the sender only publishes to this
     * topic when {@code zipBucket} is set, and personal tasks carry no
     * coordinates, so they never acquire one.
     */
    private static final Pattern COMMUNITY_TOPIC =
            Pattern.compile("^/topic/community/posts/[^/]+(?:/delete)?$");

    /**
     * Activation plan-update frames. Left on the link-possession contract
     * per the decision already recorded on {@code ACTIVATION_ACKS_TOPIC}:
     * these frames "carry no PII", unlike the ack stream beside them which
     * carries recipient names, statuses, and live coordinates. Listed
     * explicitly so it reads as a decision rather than an omission.
     */
    private static final Pattern ACTIVATION_PLAN_TOPIC =
            Pattern.compile("^/topic/activations/([^/]+)/plan$");

    /** Per-activation ack stream — PII. Owner / household / targeted member. */
    private static final Pattern ACTIVATION_ACKS_TOPIC =
            Pattern.compile("^/topic/activations/([^/]+)/acks$");

    /** Group chat: message, delete, and typing frames. */
    private static final Pattern GROUP_CHAT_TOPIC =
            Pattern.compile("^/topic/group-posts/([^/]+?)(?:/delete|/typing)?$");

    /** Group board + roster: posts, member status, member LOCATION, alert state. */
    private static final Pattern GROUP_SCOPED_TOPIC =
            Pattern.compile("^/topic/group/([^/]+)/(?:posts|posts/delete|members|members/status|members/location|status)$");

    /** Every household stream: events, presence, supplies, roster, accompaniments. */
    private static final Pattern HOUSEHOLD_TOPIC =
            Pattern.compile("^/topic/households/([^/]+)/.+$");

    /** Chat reply threads — inherit the parent message's visibility. */
    private static final Pattern GROUP_POST_COMMENTS_TOPIC =
            Pattern.compile("^/topic/group-post-comments/(\\d+)(?:/delete)?$");

    /** Community post reply threads — inherit the parent post's visibility. */
    private static final Pattern POST_COMMENTS_TOPIC =
            Pattern.compile("^/topic/post-comments/(\\d+)(?:/delete)?$");

    /** Per-user streams keyed by email: DMs and the notification inbox. */
    private static final Pattern USER_SCOPED_TOPIC =
            Pattern.compile("^/topic/(?:dm|notifications)/([^/]+?)(?:/banner)?$");

    /**
     * Lazy providers, not direct dependencies: these services reach
     * SimpMessagingTemplate (via WebSocketMessageSender), which the broker
     * config that registers the interceptor is itself constructing. Same
     * reason the interceptor holds PlanActivationService this way.
     */
    private final ObjectProvider<GroupPostService> groupPostService;
    private final ObjectProvider<PlanActivationService> planActivationService;
    private final PostRepo postRepo;
    private final PostReadAuthorizer postReadAuthorizer;

    public WebSocketTopicAuthorizer(ObjectProvider<GroupPostService> groupPostService,
                                    ObjectProvider<PlanActivationService> planActivationService,
                                    PostRepo postRepo,
                                    PostReadAuthorizer postReadAuthorizer) {
        this.groupPostService = groupPostService;
        this.planActivationService = planActivationService;
        this.postRepo = postRepo;
        this.postReadAuthorizer = postReadAuthorizer;
    }

    /**
     * True when {@code email} may subscribe to {@code destination}.
     * A blank destination is not this class's business — the broker
     * rejects it — so it is allowed through untouched.
     */
    public boolean canSubscribe(String destination, String email) {
        if (!StringUtils.hasText(destination)) return true;

        // Public before identity: the community stream needs no session
        // identity beyond the authenticated CONNECT.
        if (COMMUNITY_TOPIC.matcher(destination).matches()) return true;
        if (ACTIVATION_PLAN_TOPIC.matcher(destination).matches()) return true;

        // Everything below is private and needs a resolved identity.
        if (!StringUtils.hasText(email)) return false;

        Matcher m;

        if ((m = USER_SCOPED_TOPIC.matcher(destination)).matches()) {
            // The topic key IS the identity, so equality is the whole rule.
            return m.group(1).equalsIgnoreCase(email.trim());
        }
        if ((m = GROUP_CHAT_TOPIC.matcher(destination)).matches()) {
            return groupPostService.getObject().canReadGroup(m.group(1), email);
        }
        if ((m = GROUP_SCOPED_TOPIC.matcher(destination)).matches()) {
            return groupPostService.getObject().canReadGroup(m.group(1), email);
        }
        if ((m = HOUSEHOLD_TOPIC.matcher(destination)).matches()) {
            // A household is a Group with groupType "Household"; its topic key
            // is that group's id, so ordinary membership is the right test.
            return groupPostService.getObject().canReadGroup(m.group(1), email);
        }
        if ((m = GROUP_POST_COMMENTS_TOPIC.matcher(destination)).matches()) {
            return canReadGroupPost(m.group(1), email);
        }
        if ((m = POST_COMMENTS_TOPIC.matcher(destination)).matches()) {
            return canReadPost(m.group(1), email);
        }
        if ((m = ACTIVATION_ACKS_TOPIC.matcher(destination)).matches()) {
            return planActivationService.getObject().canReadActivationAcks(m.group(1), email);
        }

        // Unmatched destination. Deny — see the class note on default deny.
        return false;
    }

    private boolean canReadGroupPost(String rawId, String email) {
        Long id = parseId(rawId);
        if (id == null) return false;
        GroupPostService svc = groupPostService.getObject();
        return svc.getPostById(id).map(post -> svc.canRead(post, email)).orElse(false);
    }

    /**
     * Community post threads defer to {@link PostReadAuthorizer}, so the
     * bypass arms the findDtoById lane adds to its group branch reach this
     * socket path automatically rather than needing a parallel edit here.
     */
    private boolean canReadPost(String rawId, String email) {
        Long id = parseId(rawId);
        if (id == null) return false;
        return postRepo.findById(id)
                .map(post -> postReadAuthorizer.canRead(post, email))
                .orElse(false);
    }

    private static Long parseId(String raw) {
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
