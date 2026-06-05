package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Follow;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.FollowRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.PushPolicyService.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

/**
 * Follow-edge operations. Per {@code docs/PROFILE_AND_FOLLOW.md} build-
 * order step 3 — backend follow plumbing. Idempotent: tapping Follow
 * twice is a no-op rather than a 409, which matches the FE optimistic
 * pattern (the hook calls follow() on tap; if the user double-taps,
 * the second call shouldn't error).
 *
 * <p>Resolves target by id OR email so the FE can call from a profile
 * route or a post-byline avatar tap with the same helper. Self-follow
 * is rejected — there's no defensible UX for it.</p>
 *
 * <p>Email-case handling: all lookups + writes are lowercased so the
 * unique constraint catches case-only duplicates and queries don't
 * miss "Alice@x.com" vs "alice@x.com".</p>
 */
@Service
public class FollowService {

    private static final Logger logger = LoggerFactory.getLogger(FollowService.class);

    private final FollowRepo followRepo;
    private final UserInfoRepo userInfoRepo;
    private final NotificationService notificationService;

    public FollowService(FollowRepo followRepo,
                         UserInfoRepo userInfoRepo,
                         NotificationService notificationService) {
        this.followRepo = followRepo;
        this.userInfoRepo = userInfoRepo;
        this.notificationService = notificationService;
    }

    /**
     * Resolve {@code idOrEmail} to a normalized lowercase email. Tries
     * id (UUID) first, then email. Empty optional when no user matches
     * — callers turn that into a 404.
     */
    @Transactional(readOnly = true)
    public Optional<String> resolveEmail(String idOrEmail) {
        if (idOrEmail == null || idOrEmail.isBlank()) return Optional.empty();
        String key = idOrEmail.trim();

        Optional<UserInfo> hit = userInfoRepo.findById(key);
        if (hit.isEmpty()) hit = userInfoRepo.findByUserEmailIgnoreCase(key);
        return hit.map(UserInfo::getUserEmail)
                .map(s -> s.toLowerCase(Locale.ROOT));
    }

    /**
     * {@code follower} starts following {@code target}. Idempotent —
     * already-following is silently treated as success. Self-follow
     * throws {@link IllegalArgumentException}; the resource layer
     * returns 400.
     */
    @Transactional
    public void follow(String followerEmail, String targetEmail) {
        String f = normalize(followerEmail);
        String t = normalize(targetEmail);
        if (f == null || t == null) {
            throw new IllegalArgumentException("follower and target emails required");
        }
        if (f.equals(t)) {
            throw new IllegalArgumentException("Cannot follow yourself");
        }
        if (followRepo.existsByFollowerEmailAndFollowedEmail(f, t)) return;

        Follow row = new Follow();
        row.setFollowerEmail(f);
        row.setFollowedEmail(t);
        followRepo.save(row);

        // Notification side effect — wrapped in try/catch so a delivery
        // hiccup (FCM outage, log write failure, missing UserInfo row)
        // never fails the follow itself. The follow edge is the durable
        // contract; notifications are best-effort.
        dispatchFollowNotification(f, t);
    }

    /**
     * Resolve recipient + actor identities and dispatch a
     * {@link Category#FOLLOW} ("X started following you") notification
     * for a fresh follow edge.
     *
     * <p>The Follow entity has no status field today — every follow
     * succeeds instantly regardless of {@code profileVisibility}. We
     * therefore dispatch the informational FOLLOW flavor for every
     * follow event, including private targets, because sending a
     * "X wants to follow you" FOLLOW_INVITE while the edge is already
     * live would mislead the recipient into thinking there's a pending
     * approval step that doesn't exist.</p>
     *
     * <p>{@link Category#FOLLOW_INVITE} is intentionally retained in
     * {@code PushPolicyService} for the future request-approval flow
     * (Follow.status column + approval endpoints) — the FE
     * {@code NotificationCard} CATEGORY_CTAS map already branches on it,
     * so removing the enum would break forward compatibility. It is
     * simply not dispatched from here today.</p>
     *
     * <p>The actor's email + userId are carried via {@code additionalData}
     * so the FE inbox can deep-link to the actor's profile through
     * {@code useProfileNav}, which encodes the identifier into the
     * {@code /profile/:identifier} URL.</p>
     */
    private void dispatchFollowNotification(String followerEmail, String targetEmail) {
        try {
            Optional<UserInfo> targetOpt = userInfoRepo.findByUserEmailIgnoreCase(targetEmail);
            if (targetOpt.isEmpty()) return;
            UserInfo target = targetOpt.get();

            Optional<UserInfo> actorOpt = userInfoRepo.findByUserEmailIgnoreCase(followerEmail);
            UserInfo actor = actorOpt.orElse(null);
            String actorName = displayName(actor, followerEmail);
            String actorIcon = actor != null ? actor.getProfileImageURL() : null;
            String actorIdentifier = profileIdentifier(actor, followerEmail);
            String actorUserId = actor != null ? actor.getId() : null;

            // Always FOLLOW — no FOLLOW_INVITE today (see javadoc).
            String title = "New follower";
            String body = actorName + " started following you";
            String type = "follow";
            Category category = Category.FOLLOW;
            String targetUrl = "/profile/" + actorIdentifier;
            String additionalData = buildActorPayload(followerEmail, actorUserId);

            notificationService.deliverPresenceAware(
                    target.getUserEmail(),
                    title,
                    body,
                    actorName,
                    actorIcon,
                    type,
                    /* referenceId — the actor identifier so the inbox can
                       thread or de-dupe follow events from the same actor */
                    actorIdentifier,
                    targetUrl,
                    additionalData,
                    target.getFcmtoken(),
                    category,
                    /* actorUserId — written onto NotificationLog so the
                       inbox card's actor avatar deep-links to /profile
                       via useProfileNav (Status Now FE convention). */
                    actorUserId
            );
        } catch (Exception e) {
            // Notifications are best-effort — never let a dispatch
            // failure roll back the follow row.
            logger.warn("Follow notification dispatch failed (follower={}, target={}): {}",
                    followerEmail, targetEmail, e.getMessage());
        }
    }

    /**
     * Dispatch a {@link Category#FOLLOW_ACCEPTED} notification back to
     * the original requester when a private-profile follow request is
     * approved by the target. The "actor" of this notification is the
     * TARGET (the person who just accepted), so the notification routes
     * the requester to the target's profile.
     *
     * <p>No-op when either identity can't be resolved. Best-effort —
     * a delivery failure is logged but never throws.</p>
     *
     * <p>Forward-compatible hook: no resource endpoint invokes this
     * today because the pending-request flow (Follow.status column +
     * approval endpoints) doesn't exist yet. The method is intentionally
     * kept dormant so wiring it in later is a one-line call-site change
     * with no other ripple. {@link #dispatchFollowNotification} currently
     * dispatches the informational {@link Category#FOLLOW} for every
     * follow event regardless of {@code profileVisibility}.</p>
     *
     * <p>Carries accepter email + userId via {@code additionalData} so
     * the FE inbox can deep-link to the accepter's profile through
     * {@code useProfileNav}.</p>
     */
    @Transactional(readOnly = true)
    public void notifyFollowAccepted(String requesterEmail, String accepterEmail) {
        try {
            String requester = normalize(requesterEmail);
            String accepter = normalize(accepterEmail);
            if (requester == null || accepter == null || requester.equals(accepter)) return;

            Optional<UserInfo> requesterOpt = userInfoRepo.findByUserEmailIgnoreCase(requester);
            if (requesterOpt.isEmpty()) return;
            UserInfo requesterUser = requesterOpt.get();

            Optional<UserInfo> accepterOpt = userInfoRepo.findByUserEmailIgnoreCase(accepter);
            UserInfo accepterUser = accepterOpt.orElse(null);
            String accepterName = displayName(accepterUser, accepter);
            String accepterIcon = accepterUser != null ? accepterUser.getProfileImageURL() : null;
            String accepterIdentifier = profileIdentifier(accepterUser, accepter);
            String accepterUserId = accepterUser != null ? accepterUser.getId() : null;

            String title = "Follow request accepted";
            String body = accepterName + " accepted your follow request";
            String targetUrl = "/profile/" + accepterIdentifier;
            String additionalData = buildActorPayload(accepter, accepterUserId);

            notificationService.deliverPresenceAware(
                    requesterUser.getUserEmail(),
                    title,
                    body,
                    accepterName,
                    accepterIcon,
                    "follow_accepted",
                    accepterIdentifier,
                    targetUrl,
                    additionalData,
                    requesterUser.getFcmtoken(),
                    Category.FOLLOW_ACCEPTED,
                    /* actorUserId — accepter's id, so the FE inbox actor
                       avatar deep-links to their /profile. */
                    accepterUserId
            );
        } catch (Exception e) {
            logger.warn("Follow-accepted notification dispatch failed "
                            + "(requester={}, accepter={}): {}",
                    requesterEmail, accepterEmail, e.getMessage());
        }
    }

    /**
     * Build the actor-identity JSON payload that rides on
     * {@code additionalData}. The FE persists this on
     * {@code NotificationLog.additionalData} and reads
     * {@code actorEmail} / {@code actorUserId} so {@code useProfileNav}
     * can deep-link to the actor's profile from the inbox card —
     * matches the inline JSON pattern used elsewhere (e.g.
     * {@code HouseholdRitualScheduler}).
     *
     * <p>{@code actorUserId} is omitted from the JSON when null so we
     * don't write an explicit {@code "actorUserId":null} string into
     * the log row.</p>
     */
    private static String buildActorPayload(String actorEmail, String actorUserId) {
        if ((actorEmail == null || actorEmail.isBlank())
                && (actorUserId == null || actorUserId.isBlank())) {
            return null;
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        if (actorEmail != null && !actorEmail.isBlank()) {
            sb.append("\"actorEmail\":\"").append(escapeJson(actorEmail)).append("\"");
            first = false;
        }
        if (actorUserId != null && !actorUserId.isBlank()) {
            if (!first) sb.append(",");
            sb.append("\"actorUserId\":\"").append(escapeJson(actorUserId)).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    /** Minimal JSON string escaper for the two fields above (email + id
     * are tightly constrained, but we still escape quotes/backslashes
     * defensively so a malformed value can't corrupt the payload). */
    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Build a presentable actor name for notification body copy. Prefers
     * "First Last", falls back to first name, then to the email-local
     * part — matches the convention used elsewhere (e.g. PostCommentService).
     */
    private static String displayName(UserInfo user, String fallbackEmail) {
        if (user != null) {
            String first = user.getUserFirstName();
            String last = user.getUserLastName();
            String combined = ((first != null ? first.trim() : "")
                    + " "
                    + (last != null ? last.trim() : "")).trim();
            if (!combined.isEmpty()) return combined;
        }
        if (fallbackEmail != null && fallbackEmail.contains("@")) {
            return fallbackEmail.substring(0, fallbackEmail.indexOf('@'));
        }
        return "Someone";
    }

    /**
     * Resolve a routable identifier for the actor's profile URL. The
     * FE profile route accepts id OR email (UserInfoResource resolves
     * both); we prefer the stable userId when we have it and fall back
     * to email so unauthenticated lookups can still navigate.
     */
    private static String profileIdentifier(UserInfo user, String fallbackEmail) {
        if (user != null && user.getId() != null && !user.getId().isBlank()) {
            return user.getId();
        }
        return fallbackEmail;
    }

    /**
     * {@code follower} stops following {@code target}. Idempotent —
     * not-following is silently treated as success.
     */
    @Transactional
    public void unfollow(String followerEmail, String targetEmail) {
        String f = normalize(followerEmail);
        String t = normalize(targetEmail);
        if (f == null || t == null) return;
        followRepo.findByFollowerEmailAndFollowedEmail(f, t)
                .ifPresent(followRepo::delete);
    }

    /**
     * Resolve the viewer's relationship to {@code target}, used by
     * the public-profile DTO to drive the Follow button state.
     *
     * <p>Vocabulary matches the spec doc:</p>
     * <ul>
     *   <li>{@code self} — viewer is the target (no Follow CTA)</li>
     *   <li>{@code mutual} — both follow each other</li>
     *   <li>{@code follower} — viewer follows target (show "Following")</li>
     *   <li>{@code followee} — target follows viewer (show "Follow back")</li>
     *   <li>{@code none} — neither (show "Follow")</li>
     * </ul>
     *
     * <p>{@code blocked} lands at step 5 alongside the Block entity.</p>
     */
    /**
     * Resolve viewer's follow relationship to target. Vocabulary:
     * {@code self / mutual / follower / followee / none}.
     *
     * <p>Block-awareness lives at the call site, not here, to avoid a
     * BlockService → FollowService → BlockService construction cycle.
     * The only consumer ({@code UserInfoService.getPublicProfile})
     * checks {@code blockService.isAnyBlock} first and returns 404
     * before this runs, so the "blocked" relationship value isn't
     * reachable via that path. Future callers that need it should
     * compose: check block first, then call this.</p>
     */
    @Transactional(readOnly = true)
    public String getRelationship(String viewerEmail, String targetEmail) {
        String v = normalize(viewerEmail);
        String t = normalize(targetEmail);
        if (v == null || t == null) return "none";
        if (v.equals(t)) return "self";

        boolean viewerFollowsTarget = followRepo.existsByFollowerEmailAndFollowedEmail(v, t);
        boolean targetFollowsViewer = followRepo.existsByFollowerEmailAndFollowedEmail(t, v);

        if (viewerFollowsTarget && targetFollowsViewer) return "mutual";
        if (viewerFollowsTarget) return "follower";
        if (targetFollowsViewer) return "followee";
        return "none";
    }

    /**
     * Lightweight email lists for the Followers / Following management
     * page (PROFILE_AND_FOLLOW step 6). Caller resolves these to
     * {@link io.sitprep.sitprepapi.dto.ProfileSummaryDto} via the
     * existing {@code getProfileSummariesByEmails} batch helper.
     */
    @Transactional(readOnly = true)
    public java.util.List<String> followingEmails(String viewerEmail) {
        String v = normalize(viewerEmail);
        if (v == null) return java.util.List.of();
        return followRepo.findByFollowerEmail(v).stream()
                .map(io.sitprep.sitprepapi.domain.Follow::getFollowedEmail)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Transactional(readOnly = true)
    public java.util.List<String> followerEmails(String viewerEmail) {
        String v = normalize(viewerEmail);
        if (v == null) return java.util.List.of();
        return followRepo.findByFollowedEmail(v).stream()
                .map(io.sitprep.sitprepapi.domain.Follow::getFollowerEmail)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * How many users this person is following. Folded onto
     * {@link io.sitprep.sitprepapi.dto.PublicProfileDto} for the
     * Following/Followers counter row on the public profile.
     */
    @Transactional(readOnly = true)
    public long followingCount(String viewerEmail) {
        String v = normalize(viewerEmail);
        if (v == null) return 0L;
        return followRepo.countByFollowerEmail(v);
    }

    /**
     * How many users follow this person. Same DTO surfacing as
     * {@link #followingCount(String)}.
     */
    @Transactional(readOnly = true)
    public long followerCount(String viewerEmail) {
        String v = normalize(viewerEmail);
        if (v == null) return 0L;
        return followRepo.countByFollowedEmail(v);
    }

    private static String normalize(String email) {
        if (email == null) return null;
        String t = email.trim();
        if (t.isEmpty()) return null;
        return t.toLowerCase(Locale.ROOT);
    }
}
