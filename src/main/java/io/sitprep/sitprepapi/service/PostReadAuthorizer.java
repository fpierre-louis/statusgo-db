package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.PostKind;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.repo.GroupRepo;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * The single answer to "may this viewer read this post?".
 *
 * <p>Before this class existed the question was answered nowhere — every
 * read path returned whatever the query produced, and the two Javadoc
 * comments that claimed otherwise ({@code PostKind.TASK}'s "never surfaces
 * in the community feed", {@code Post}'s "visible only to that group's
 * members") described a filter that had never been written. See
 * {@code docs/audits/personal-task-feed-exposure.md} and
 * {@code docs/audits/post-by-id-authorization.md}.</p>
 *
 * <p><b>Two branches, two owners.</b> The groupless branch is complete and
 * enforced. The group-scoped branch is a working membership check that is
 * deliberately minimal — the read rule for group content is the
 * {@code findDtoById} lane's to finish, and the bypass arms it still needs
 * are named on {@link #canRead} below. Do not extend the group branch here
 * without coordinating with that lane; it is the owner.</p>
 */
@Component
public class PostReadAuthorizer {

    private final GroupRepo groupRepo;

    public PostReadAuthorizer(GroupRepo groupRepo) {
        this.groupRepo = groupRepo;
    }

    /**
     * True when {@code viewerEmail} may read {@code post}.
     *
     * <p><b>Groupless rows</b> — personal-scope kinds
     * ({@link PostKind#isPersonalScope}) are readable only by their
     * requester; every other kind is community content and is public to
     * any viewer. This arm is complete.</p>
     *
     * <p><b>Group-scoped rows</b> — readable by owner / admin / member of
     * the owning group. This arm is intentionally the floor, not the final
     * rule. The {@code findDtoById} lane owns these still-missing arms:
     * the claimer group's members reading a community task their group
     * claimed ({@code claimedByGroupId}); whether PENDING members read; and
     * platform-admin / moderation bypass. Add them there, not here.</p>
     *
     * <p>A null or blank viewer is treated as anonymous and short-circuits
     * before any group lookup — which is what makes this safe to call from
     * the unauthenticated share-preview path without adding a query per
     * crawler hit.</p>
     */
    public boolean canRead(Post post, String viewerEmail) {
        if (post == null) return false;

        String groupId = post.getGroupId();
        if (groupId == null || groupId.isBlank()) {
            // Groupless. Personal-scope kinds are private to their author;
            // everything else is a deliberate broadcast to the neighborhood.
            if (!PostKind.isPersonalScope(post.getKind())) return true;
            return isRequester(post, viewerEmail);
        }

        // Group-scoped. Anonymous readers never qualify, and short-circuiting
        // here keeps the share path lookup-free.
        if (viewerEmail == null || viewerEmail.isBlank()) return false;
        Group group = groupRepo.findByGroupId(groupId.trim()).orElse(null);
        if (group == null) return false;
        return isMemberOf(group, viewerEmail);
    }

    /**
     * True when {@code post} is a personal-scope row that {@code viewerEmail}
     * does not own — the groupless arm of {@link #canRead} on its own.
     *
     * <p>For call sites that must apply the personal rule WITHOUT taking a
     * position on group-scoped visibility, because that decision belongs to
     * the {@code findDtoById} lane. {@code /api/posts/by-author/{email}} is
     * the case: it returns group-scoped rows today, and tightening those
     * here would land the other lane's change under this one's review.</p>
     */
    public static boolean isPersonalHiddenFrom(Post post, String viewerEmail) {
        if (post == null) return false;
        String groupId = post.getGroupId();
        if (groupId != null && !groupId.isBlank()) return false;
        if (!PostKind.isPersonalScope(post.getKind())) return false;
        return !isRequester(post, viewerEmail);
    }

    private static boolean isRequester(Post post, String viewerEmail) {
        if (viewerEmail == null || viewerEmail.isBlank()) return false;
        String requester = post.getRequesterEmail();
        return requester != null && requester.equalsIgnoreCase(viewerEmail.trim());
    }

    // Mirrors GroupPostService.isMemberOf. Duplicated rather than shared
    // because that copy is the chat lane's and this one will grow the
    // bypass arms above; the findDtoById lane should collapse the two once
    // it settles the final group read rule.
    private static boolean isMemberOf(Group group, String email) {
        if (email == null || email.isBlank()) return false;
        if (group.getOwnerEmail() != null && group.getOwnerEmail().equalsIgnoreCase(email)) {
            return true;
        }
        return containsIgnoreCase(group.getAdminEmails(), email)
                || containsIgnoreCase(group.getMemberEmails(), email);
    }

    private static boolean containsIgnoreCase(Collection<String> emails, String email) {
        return emails != null && emails.stream()
                .anyMatch(e -> e != null && e.equalsIgnoreCase(email));
    }
}
