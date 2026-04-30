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
    // BlockService lookup is lazy via setter to avoid a circular
    // construction cycle (BlockService depends on FollowService for
    // unfollow side-effects when a block is created).
    private BlockService blockService;

    public FollowService(FollowRepo followRepo, UserInfoRepo userInfoRepo) {
        this.followRepo = followRepo;
        this.userInfoRepo = userInfoRepo;
    }

    /**
     * Spring sets this after construction to break the cycle —
     * BlockService(FollowService) ↔ FollowService.getRelationship
     * (BlockService). Setter injection only, no @Autowired here so
     * Spring picks the field via reflection on the public method.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public void setBlockService(BlockService blockService) {
        this.blockService = blockService;
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
    @Transactional(readOnly = true)
    public String getRelationship(String viewerEmail, String targetEmail) {
        String v = normalize(viewerEmail);
        String t = normalize(targetEmail);
        if (v == null || t == null) return "none";
        if (v.equals(t)) return "self";

        // Block trumps follow per docs/PROFILE_AND_FOLLOW.md step 5.
        // Only the viewer-blocked-target direction surfaces here — the
        // reverse (target blocked viewer) results in a 404 at the
        // resource layer, so this code path doesn't see it.
        if (blockService != null && blockService.blocks(v, t)) {
            return "blocked";
        }

        boolean viewerFollowsTarget = followRepo.existsByFollowerEmailAndFollowedEmail(v, t);
        boolean targetFollowsViewer = followRepo.existsByFollowerEmailAndFollowedEmail(t, v);

        if (viewerFollowsTarget && targetFollowsViewer) return "mutual";
        if (viewerFollowsTarget) return "follower";
        if (targetFollowsViewer) return "followee";
        return "none";
    }

    private static String normalize(String email) {
        if (email == null) return null;
        String t = email.trim();
        if (t.isEmpty()) return null;
        return t.toLowerCase(Locale.ROOT);
    }
}
