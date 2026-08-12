package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.PostKind;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.TaskAssigneeRepo;
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
 * <p><b>Both branches are complete.</b> The groupless branch landed with
 * the personal-task fix; the group-scoped branch and its bypass arms landed
 * with the {@code findDtoById} fix. {@link #canRead} is the authoritative
 * statement of the rule — if a call site needs something it does not grant,
 * change it here rather than adding a second predicate at the call site.</p>
 */
@Component
public class PostReadAuthorizer {

    private final GroupRepo groupRepo;
    private final TaskAssigneeRepo taskAssigneeRepo;

    public PostReadAuthorizer(GroupRepo groupRepo, TaskAssigneeRepo taskAssigneeRepo) {
        this.groupRepo = groupRepo;
        this.taskAssigneeRepo = taskAssigneeRepo;
    }

    /**
     * True when {@code viewerEmail} may read {@code post}. This is the whole
     * rule; the arms below are decided, not provisional.
     *
     * <p><b>Groupless rows</b> — personal-scope kinds
     * ({@link PostKind#isPersonalScope}) are readable only by their
     * requester; every other kind is community content and is public to
     * any viewer.</p>
     *
     * <p><b>Group-scoped rows</b> — readable by any of:</p>
     * <ol>
     *   <li><b>Group membership</b> — owner, admin, or member of the owning
     *       group. The floor.</li>
     *   <li><b>The requester</b> — the row's own author, unconditionally.
     *       Mirrors {@code PostResource.ensureCanEditTask} ("Author may
     *       always edit their own post") so read is never narrower than
     *       write, and covers an author who has since left the group.</li>
     *   <li><b>The claimer group's members</b> — when a group has claimed
     *       the row ({@code claimedByGroupId}), its members read it. Required
     *       by the entity's own contract: {@code Post}'s class doc states
     *       "both the requester and the claimer-group's members see live
     *       status", and {@code WebSocketMessageSender} already broadcasts
     *       these rows to {@code /topic/group/{claimedByGroupId}/posts}.
     *       Without this arm the claiming crew is pushed live frames for
     *       work they cannot fetch.</li>
     *   <li><b>Assignees</b> — anyone on {@code task_assignee} for the row,
     *       LEAD or HELPER. Assignment already requires group membership
     *       ({@code ensureTargetIsGroupMember}), so this changes nothing in
     *       normal operation; it covers the worker who was assigned and has
     *       since left the group, who would otherwise lose their own work.</li>
     * </ol>
     *
     * <p><b>Decided against, deliberately:</b></p>
     * <ul>
     *   <li><b>PENDING members do not read.</b> The write gate rejects them
     *       ({@code GroupPostSecurityTest}) and a request to join is not a
     *       grant of history. The sanctioned pre-join surface is
     *       {@code GroupPreviewDto}, which is sanitized by design.</li>
     *   <li><b>No moderation bypass.</b> Moderation never fetches a post by
     *       id — {@code CommunityReportService} captures a server-side
     *       {@code contentPreview} at report time and the console reads that.</li>
     *   <li><b>No platform-admin bypass.</b> {@code PlatformAccessService}
     *       could supply one, but no surface needs it, and an unused bypass
     *       is attack surface. Add it when a console feature actually reads
     *       post content.</li>
     *   <li><b>No agency/staff bypass.</b> Civic reports tag agencies via
     *       {@code taggedAgencyGroupId}, not {@code groupId}, so they are
     *       groupless and the agency merge path never crosses this gate.
     *       Confirmed against prod 2026-08-11: zero group-scoped
     *       {@code civic-report} rows.</li>
     * </ul>
     *
     * <p>A null or blank viewer is treated as anonymous and short-circuits
     * before any lookup — which is what makes this safe to call from the
     * unauthenticated share-preview path without adding a query per crawler
     * hit.</p>
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

        // Arm 2 — the author, before any query.
        if (isRequester(post, viewerEmail)) return true;

        // Arm 1 — membership in the owning group.
        if (isMemberOfGroup(groupId, viewerEmail)) return true;

        // Arm 3 — membership in the group that claimed the row.
        String claimedBy = post.getClaimedByGroupId();
        if (claimedBy != null && !claimedBy.isBlank()
                && !claimedBy.equalsIgnoreCase(groupId)
                && isMemberOfGroup(claimedBy, viewerEmail)) {
            return true;
        }

        // Arm 4 — assigned to the row. Last because it is the only DB hit
        // that is not a group lookup, and the arms above cover the common case.
        return post.getId() != null
                && taskAssigneeRepo.existsByPostIdAndEmailIgnoreCase(post.getId(), viewerEmail.trim());
    }

    /**
     * Membership in the group with this public id. False when the group is
     * unknown, so an enumerating caller cannot distinguish "no such group"
     * from "not yours". Public because the group-board listing needs the
     * membership floor on its own, without the per-row arms.
     */
    public boolean isMemberOfGroup(String groupId, String viewerEmail) {
        if (groupId == null || groupId.isBlank()) return false;
        Group group = groupRepo.findByGroupId(groupId.trim()).orElse(null);
        return group != null && isMemberOf(group, viewerEmail);
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
