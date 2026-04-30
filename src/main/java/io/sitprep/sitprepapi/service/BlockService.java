package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Block;
import io.sitprep.sitprepapi.repo.BlockRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Block-edge operations. Per {@code docs/PROFILE_AND_FOLLOW.md} build-
 * order step 5 — block is the safety primitive that trumps everything
 * else. Symmetric: {@link #isAnyBlock} returns true whether viewer
 * blocked target OR target blocked viewer, so either side hides the
 * other from profile reads + feed merges.
 *
 * <p>Idempotent like {@link FollowService}: tapping Block twice is a
 * no-op rather than a 409, since the FE optimistic pattern can fire
 * twice on anxious taps.</p>
 *
 * <p>Block side-effect: also unfollows in both directions. Per the
 * spec's plain-English rules: "Block = unfollow + hide from feed
 * forever." The follow rows are torn down so the relationship is
 * cleanly severed; if the blocker later unblocks, neither side will
 * silently follow the other again.</p>
 */
@Service
public class BlockService {

    private final BlockRepo blockRepo;
    private final FollowService followService;

    public BlockService(BlockRepo blockRepo, FollowService followService) {
        this.blockRepo = blockRepo;
        this.followService = followService;
    }

    /**
     * {@code blocker} blocks {@code target}. Idempotent — already-
     * blocked is silent success. Self-block is rejected (no defensible
     * UX). As a side effect, both follow edges (blocker→target and
     * target→blocker) are cleaned up so the relationship is fully
     * severed.
     */
    @Transactional
    public void block(String blockerEmail, String targetEmail) {
        String b = normalize(blockerEmail);
        String t = normalize(targetEmail);
        if (b == null || t == null) {
            throw new IllegalArgumentException("blocker and target emails required");
        }
        if (b.equals(t)) {
            throw new IllegalArgumentException("Cannot block yourself");
        }

        // Tear down any existing follow edges in both directions
        // BEFORE writing the block row, so a "block" from a mutual
        // state doesn't leave stranded follow rows.
        followService.unfollow(b, t);
        followService.unfollow(t, b);

        if (blockRepo.existsByBlockerEmailAndBlockedEmail(b, t)) return;

        Block row = new Block();
        row.setBlockerEmail(b);
        row.setBlockedEmail(t);
        blockRepo.save(row);
    }

    @Transactional
    public void unblock(String blockerEmail, String targetEmail) {
        String b = normalize(blockerEmail);
        String t = normalize(targetEmail);
        if (b == null || t == null) return;
        blockRepo.findByBlockerEmailAndBlockedEmail(b, t)
                .ifPresent(blockRepo::delete);
    }

    /** True when {@code blocker} has explicitly blocked {@code target}. */
    @Transactional(readOnly = true)
    public boolean blocks(String blockerEmail, String targetEmail) {
        String b = normalize(blockerEmail);
        String t = normalize(targetEmail);
        if (b == null || t == null) return false;
        return blockRepo.existsByBlockerEmailAndBlockedEmail(b, t);
    }

    /**
     * Symmetric block check — true when either party has blocked the
     * other. Drives the 404 stance on profile reads and the feed-
     * suppression filter (per the spec: "Block trumps everything: a
     * blocked user sees you don't exist").
     */
    @Transactional(readOnly = true)
    public boolean isAnyBlock(String a, String b) {
        String aN = normalize(a);
        String bN = normalize(b);
        if (aN == null || bN == null) return false;
        if (aN.equals(bN)) return false;
        return blockRepo.existsByBlockerEmailAndBlockedEmail(aN, bN)
                || blockRepo.existsByBlockerEmailAndBlockedEmail(bN, aN);
    }

    /**
     * Set of all emails {@code viewer} has either blocked OR been
     * blocked by — used by the community-feed merge to suppress posts
     * from any party in a block relationship with the viewer. One
     * round trip per request; the union is small in practice.
     */
    @Transactional(readOnly = true)
    public Set<String> getBlockSet(String viewerEmail) {
        String v = normalize(viewerEmail);
        if (v == null) return Set.of();
        List<Block> outgoing = blockRepo.findByBlockerEmail(v);
        List<Block> incoming = blockRepo.findByBlockedEmail(v);
        Set<String> out = outgoing.stream()
                .map(Block::getBlockedEmail)
                .collect(Collectors.toSet());
        for (Block b : incoming) {
            out.add(b.getBlockerEmail());
        }
        return out;
    }

    private static String normalize(String email) {
        if (email == null) return null;
        String t = email.trim();
        if (t.isEmpty()) return null;
        return t.toLowerCase(Locale.ROOT);
    }
}
