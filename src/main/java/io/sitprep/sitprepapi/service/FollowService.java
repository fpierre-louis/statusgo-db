package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Follow;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.FollowRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
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

    private final FollowRepo followRepo;
    private final UserInfoRepo userInfoRepo;

    public FollowService(FollowRepo followRepo, UserInfoRepo userInfoRepo) {
        this.followRepo = followRepo;
        this.userInfoRepo = userInfoRepo;
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
