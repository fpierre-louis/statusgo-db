package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.GroupRole;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.GroupPost;
import io.sitprep.sitprepapi.domain.GroupPostComment;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GroupInviteRedemptionRepo;
import io.sitprep.sitprepapi.repo.GroupInviteRepo;
import io.sitprep.sitprepapi.repo.GroupMutePrefRepo;
import io.sitprep.sitprepapi.repo.GroupPostCommentReactionRepo;
import io.sitprep.sitprepapi.repo.GroupPostCommentRepo;
import io.sitprep.sitprepapi.repo.GroupPostReactionRepo;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.GroupReadStateRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.GroupTopicEvictor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deleting a group, as one transaction (BE-09, B5 2026-10-14).
 *
 * <p>It used to be a bare row delete ({@code groupRepo.delete}). A check-in
 * running at that moment never ended: no "Cleared" frame for open pages, no
 * household log entry, and the Live Activity on members' lock screens kept a
 * running card for a group that no longer existed. Members whose BASE was the
 * deleted household kept pointing at it, so {@code /api/me} read plans for a
 * household nobody could open. Invites, redemptions, mute prefs and read
 * state were orphaned, and parent/child groups kept the dead id in their
 * link lists.</p>
 *
 * <p>Now, in order:</p>
 * <ol>
 *   <li>A running check-in ends through {@link GroupService#setAlert} — the
 *       one alert path, so the frame, the household log and the Live
 *       Activity end hook all happen exactly as for a manual end.</li>
 *   <li>Parent and child groups drop the link on their side.</li>
 *   <li>Everyone on the roster loses the id from their joined/managed ids.</li>
 *   <li>Invites and their redemptions, mute prefs and read state are
 *       deleted.</li>
 *   <li>Posts are KEPT unless the owner asks otherwise
 *       ({@code ?posts=delete}): the owner's call (AUDIT BE-09), and kept is
 *       the conservative default — nobody can read them once the group is
 *       gone ({@code canReadGroup} needs the group), and nothing is lost if
 *       the call turns out wrong.</li>
 *   <li>The group row is deleted and flushed, and only then is each member
 *       whose base it was re-anchored
 *       ({@link HouseholdProvisioningService#ensureBaseHousehold}: another
 *       household they belong to, else a new personal one). Re-anchoring
 *       before the delete could pick the dying household again.</li>
 *   <li>After the commit, every open socket subscription to the group's
 *       topics is dropped ({@link GroupTopicEvictor}).</li>
 * </ol>
 *
 * <p>Not done here, deliberately: a household's plan rows (meeting places,
 * contacts, supplies…) and task/work-order posts keyed by the group stay as
 * they are (several tables cascade at the database by their own foreign
 * keys). Deleting a family's plan data is a separate owner decision.</p>
 */
@Service
public class GroupDeletionService {

    private static final Logger log = LoggerFactory.getLogger(GroupDeletionService.class);

    /** What happens to the group's conversation. */
    public enum Posts { KEEP, DELETE }

    /** What a delete did, for the log and the tests. */
    public record Result(boolean checkInEnded, int linksDropped, int reanchored,
                         int invitesDeleted, int mutePrefsDeleted, int readStatesDeleted,
                         int postsDeleted) {}

    private final GroupRepo groupRepo;
    private final UserInfoRepo userInfoRepo;
    private final GroupService groupService;
    private final HouseholdProvisioningService householdProvisioning;
    private final GroupInviteRepo inviteRepo;
    private final GroupInviteRedemptionRepo redemptionRepo;
    private final GroupMutePrefRepo mutePrefRepo;
    private final GroupReadStateRepo readStateRepo;
    private final GroupPostRepo postRepo;
    private final GroupPostCommentRepo commentRepo;
    private final GroupPostReactionRepo postReactionRepo;
    private final GroupPostCommentReactionRepo commentReactionRepo;
    private final ObjectProvider<GroupTopicEvictor> topicEvictor;

    public GroupDeletionService(GroupRepo groupRepo,
                                UserInfoRepo userInfoRepo,
                                GroupService groupService,
                                HouseholdProvisioningService householdProvisioning,
                                GroupInviteRepo inviteRepo,
                                GroupInviteRedemptionRepo redemptionRepo,
                                GroupMutePrefRepo mutePrefRepo,
                                GroupReadStateRepo readStateRepo,
                                GroupPostRepo postRepo,
                                GroupPostCommentRepo commentRepo,
                                GroupPostReactionRepo postReactionRepo,
                                GroupPostCommentReactionRepo commentReactionRepo,
                                ObjectProvider<GroupTopicEvictor> topicEvictor) {
        this.groupRepo = groupRepo;
        this.userInfoRepo = userInfoRepo;
        this.groupService = groupService;
        this.householdProvisioning = householdProvisioning;
        this.inviteRepo = inviteRepo;
        this.redemptionRepo = redemptionRepo;
        this.mutePrefRepo = mutePrefRepo;
        this.readStateRepo = readStateRepo;
        this.postRepo = postRepo;
        this.commentRepo = commentRepo;
        this.postReactionRepo = postReactionRepo;
        this.commentReactionRepo = commentReactionRepo;
        this.topicEvictor = topicEvictor;
    }

    /**
     * Delete {@code groupId}. Owner only (403; the resource checks first, and
     * this checks again so no other caller can skip it).
     */
    @Transactional
    public Result delete(String groupId, String caller, Posts posts) {
        Group g = groupService.getGroupByPublicId(groupId);
        if (caller == null || GroupRole.fromGroup(g, caller) != GroupRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Group owner role required");
        }
        final String id = g.getGroupId();

        // 1. The check-in ends the one way a check-in ends.
        boolean ended = false;
        if ("Active".equalsIgnoreCase(g.getAlert())) {
            g = groupService.setAlert(id, false, caller);
            ended = true;
        }

        // 2. Links, both sides.
        int links = 0;
        for (String parentId : copy(g.getParentGroupIDs())) {
            links += dropLink(parentId, id, true);
        }
        for (String childId : copy(g.getSubGroupIDs())) {
            links += dropLink(childId, id, false);
        }

        // 3. People: joined/managed ids; bases to re-anchor after the delete.
        Set<String> roster = new LinkedHashSet<>();
        addAll(roster, g.getMemberEmails());
        addAll(roster, g.getAdminEmails());
        if (g.getOwnerEmail() != null) roster.add(g.getOwnerEmail().trim().toLowerCase(Locale.ROOT));
        List<UserInfo> reanchor = new ArrayList<>();
        if (!roster.isEmpty()) {
            for (UserInfo u : userInfoRepo.findByUserEmailIn(new ArrayList<>(roster))) {
                boolean changed = false;
                if (u.getJoinedGroupIDs() != null && u.getJoinedGroupIDs().contains(id)) {
                    Set<String> j = new HashSet<>(u.getJoinedGroupIDs());
                    j.remove(id);
                    u.setJoinedGroupIDs(j);
                    changed = true;
                }
                if (u.getManagedGroupIDs() != null && u.getManagedGroupIDs().contains(id)) {
                    Set<String> m = new HashSet<>(u.getManagedGroupIDs());
                    m.remove(id);
                    u.setManagedGroupIDs(m);
                    changed = true;
                }
                if (id.equals(u.getBaseHouseholdId())) {
                    u.setBaseHouseholdId(null);
                    reanchor.add(u);
                    changed = true;
                }
                if (changed) userInfoRepo.save(u);
            }
        }

        // 4. Invites, mutes, read state.
        var redemptions = redemptionRepo.findByGroupId(id);
        redemptionRepo.deleteAll(redemptions);
        var invites = inviteRepo.findByGroupId(id);
        inviteRepo.deleteAll(invites);
        var mutes = mutePrefRepo.findByGroupId(id);
        mutePrefRepo.deleteAll(mutes);
        var reads = readStateRepo.findByGroupId(id);
        readStateRepo.deleteAll(reads);

        // 5. The conversation: kept unless the owner said otherwise.
        int postsDeleted = 0;
        if (posts == Posts.DELETE) {
            postsDeleted = deleteConversation(id);
        }

        // 6. The row, flushed, THEN the bases (or ensureBaseHousehold could
        //    choose the dying household again).
        groupRepo.delete(g);
        groupRepo.flush();
        for (UserInfo u : reanchor) {
            householdProvisioning.ensureBaseHousehold(u);
        }

        // 7. Open sockets stop hearing the group.
        GroupTopicEvictor evictor = topicEvictor.getIfAvailable();
        if (evictor != null) afterCommitOrNow(() -> evictor.evictAll(id));

        Result result = new Result(ended, links, reanchor.size(), invites.size(),
                mutes.size(), reads.size(), postsDeleted);
        log.info("Group {} deleted by its owner: {}", id, result);
        return result;
    }

    /** Remove {@code deletedId} from the other group's link list. 1 when it was there. */
    private int dropLink(String otherId, String deletedId, boolean otherIsParent) {
        if (otherId == null || otherId.isBlank() || otherId.equals(deletedId)) return 0;
        return groupRepo.findByGroupId(otherId).map(other -> {
            List<String> ids = copy(otherIsParent ? other.getSubGroupIDs() : other.getParentGroupIDs());
            if (!ids.remove(deletedId)) return 0;
            if (otherIsParent) other.setSubGroupIDs(ids); else other.setParentGroupIDs(ids);
            groupRepo.save(other);
            return 1;
        }).orElse(0);
    }

    /** Posts, their comments and both kinds of reaction. Returns the post count. */
    private int deleteConversation(String groupId) {
        List<GroupPost> groupPosts = postRepo.findPostsByGroupId(groupId);
        if (groupPosts.isEmpty()) return 0;
        List<Long> postIds = groupPosts.stream().map(GroupPost::getId).toList();
        List<GroupPostComment> comments = commentRepo.findAllByPostIdInOrderByPostIdAscTimestampAsc(postIds);
        if (!comments.isEmpty()) {
            List<Long> commentIds = comments.stream().map(GroupPostComment::getId).toList();
            commentReactionRepo.deleteAll(commentReactionRepo.findByGroupPostCommentIdIn(commentIds));
            commentRepo.deleteAll(comments);
        }
        postReactionRepo.deleteAll(postReactionRepo.findByPostIdIn(postIds));
        postRepo.deleteAll(groupPosts);
        return groupPosts.size();
    }

    private static List<String> copy(List<String> list) {
        List<String> out = new ArrayList<>();
        if (list != null) for (String s : list) if (s != null) out.add(s);
        return out;
    }

    private static void addAll(Set<String> into, List<String> emails) {
        if (emails == null) return;
        for (String e : emails) {
            if (e != null && !e.isBlank()) into.add(e.trim().toLowerCase(Locale.ROOT));
        }
    }

    private static void afterCommitOrNow(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { r.run(); }
            });
        } else {
            r.run();
        }
    }
}
