package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.PostConfirm;
import io.sitprep.sitprepapi.domain.PostFollow;
import io.sitprep.sitprepapi.dto.DtoImages;
import io.sitprep.sitprepapi.dto.MemberAvatar;
import io.sitprep.sitprepapi.dto.ThreadContextDto;
import io.sitprep.sitprepapi.repo.PostConfirmRepo;
import io.sitprep.sitprepapi.repo.PostFollowRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The viewer's relationship to one thread: following it, and who saw it too
 * (Community Thread C, B2 / V86).
 *
 * <p>Both existed as design only — the thread header's Follow bell and the
 * "seen by" facepile were held back (rulings Q4/Q5) because neither fact was
 * on the wire. This puts them there, and makes following DO something:
 * {@link #followerEmailsToNotify} is read by {@code PostCommentService} on
 * every new reply.</p>
 *
 * <p>Every entry point is gated on {@link PostReadAuthorizer#canRead}: you
 * cannot follow, or see who confirmed, a post you cannot read. Unreadable and
 * unknown answer the same (empty), so a probe cannot tell them apart.</p>
 */
@Service
public class ThreadContextService {

    static final int MAX_FACES = 3;

    private final PostRepo postRepo;
    private final PostFollowRepo followRepo;
    private final PostConfirmRepo confirmRepo;
    private final UserInfoRepo userInfoRepo;
    private final BlockService blockService;
    private final PostReadAuthorizer readAuthorizer;

    public ThreadContextService(PostRepo postRepo,
                                PostFollowRepo followRepo,
                                PostConfirmRepo confirmRepo,
                                UserInfoRepo userInfoRepo,
                                BlockService blockService,
                                PostReadAuthorizer readAuthorizer) {
        this.postRepo = postRepo;
        this.followRepo = followRepo;
        this.confirmRepo = confirmRepo;
        this.userInfoRepo = userInfoRepo;
        this.blockService = blockService;
        this.readAuthorizer = readAuthorizer;
    }

    private Optional<Post> readable(Long postId, String viewer) {
        if (postId == null || viewer == null) return Optional.empty();
        return postRepo.findById(postId).filter(p -> readAuthorizer.canRead(p, viewer));
    }

    private static String norm(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /** The thread page's read. Empty when the post is unknown or unreadable. */
    @Transactional(readOnly = true)
    public Optional<ThreadContextDto> context(Long postId, String viewer) {
        return readable(postId, viewer).map(p -> new ThreadContextDto(
                followRepo.existsByPostIdAndUserEmailIgnoreCase(p.getId(), norm(viewer)),
                recentConfirmers(p.getId(), viewer)));
    }

    /** Follow (idempotent). Empty when the post is unknown or unreadable. */
    @Transactional
    public Optional<ThreadContextDto.FollowResult> follow(Long postId, String viewer) {
        return readable(postId, viewer).map(p -> {
            String e = norm(viewer);
            if (!followRepo.existsByPostIdAndUserEmailIgnoreCase(p.getId(), e)) {
                try {
                    PostFollow f = new PostFollow();
                    f.setPostId(p.getId());
                    f.setUserEmail(e);
                    // saveAndFlush so a racing duplicate surfaces here (caught)
                    // instead of poisoning the commit — same as addConfirm.
                    followRepo.saveAndFlush(f);
                } catch (DataIntegrityViolationException dup) {
                    // A concurrent follow for the same (post, user) landed first.
                }
            }
            return new ThreadContextDto.FollowResult(true);
        });
    }

    /** Unfollow (idempotent). Empty when the post is unknown or unreadable. */
    @Transactional
    public Optional<ThreadContextDto.FollowResult> unfollow(Long postId, String viewer) {
        return readable(postId, viewer).map(p -> {
            followRepo.deleteByPostAndUser(p.getId(), norm(viewer));
            return new ThreadContextDto.FollowResult(false);
        });
    }

    /**
     * Up to three faces for "seen by": the viewer first when they confirmed
     * (the pill beside the faces says "You saw it", so the first face is
     * theirs), then the most recent others. A block relationship in either
     * direction skips the person — someone you blocked does not reappear as a
     * face, and you do not appear to someone who blocked you.
     */
    List<MemberAvatar> recentConfirmers(Long postId, String viewer) {
        String me = norm(viewer);
        Set<String> emails = new LinkedHashSet<>();
        if (me != null && confirmRepo.findByPostIdAndUserEmailIgnoreCase(postId, me).isPresent()) {
            emails.add(me);
        }
        for (PostConfirm c : confirmRepo.findTop10ByPostIdOrderByCreatedAtDesc(postId)) {
            if (emails.size() >= MAX_FACES) break;
            String e = norm(c.getUserEmail());
            if (e == null || e.equals(me)) continue;
            if (me != null && blockService.isAnyBlock(me, e)) continue;
            emails.add(e);
        }
        List<MemberAvatar> out = new ArrayList<>();
        for (String e : emails) {
            // A confirm whose account is gone renders no face rather than a
            // placeholder person — the count still says how many.
            userInfoRepo.findByUserEmailIgnoreCase(e).ifPresent(u -> out.add(
                    new MemberAvatar(u.getId(), u.getUserFirstName(), DtoImages.avatar(u.getProfileImageUrl()))));
        }
        return out;
    }

    /**
     * Who gets "X replied to a post you follow" for a new reply. Excludes the
     * replier, the post author (they get {@code comment_on_task} already), and
     * anyone in {@code alreadyNotified} (the @-mentioned: the more specific
     * push wins). A block relationship with the replier skips the follower.
     */
    @Transactional(readOnly = true)
    public List<String> followerEmailsToNotify(Long postId, String replierEmail,
                                               String authorEmail, Set<String> alreadyNotified) {
        String replier = norm(replierEmail);
        String author = norm(authorEmail);
        Set<String> skip = new LinkedHashSet<>();
        if (alreadyNotified != null) alreadyNotified.forEach(e -> skip.add(norm(e)));
        List<String> out = new ArrayList<>();
        for (PostFollow f : followRepo.findByPostId(postId)) {
            String e = norm(f.getUserEmail());
            if (e == null || e.equals(replier) || e.equals(author) || skip.contains(e)) continue;
            if (replier != null && blockService.isAnyBlock(e, replier)) continue;
            out.add(e);
        }
        return out;
    }
}
