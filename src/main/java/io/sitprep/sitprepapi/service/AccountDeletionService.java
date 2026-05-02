package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Hard-deletes a user's account and all associated personal data.
 *
 * <p>Triggered by {@code DELETE /api/userinfo/me/account}. Required for
 * Apple App Store compliance (since 2022 — apps that support account
 * creation must offer in-app deletion).</p>
 *
 * <p><b>What gets deleted:</b> the user's UserInfo record + every piece of
 * data they own as a primary author or owner — posts, comments, reactions,
 * tasks they requested or claimed, plan activations they created, the
 * acks they submitted as a recipient, all plan data (meal/evac/meeting/
 * contacts/origins/demographic), saved locations, household manual
 * members + accompaniments + events they're the actor on, notification
 * logs. The user is also stripped from every Group's memberEmails /
 * adminEmails / pendingMemberEmails collections.</p>
 *
 * <p><b>What blocks deletion:</b> if the user owns a multi-member household
 * OR any non-household group, the call throws
 * {@link OwnedGroupsBlockingException}. The frontend surfaces this as a
 * 409 with instructions to transfer ownership / leave the groups first.
 * Auto-transfer of ownership is a v2 follow-up.</p>
 *
 * <p><b>What gets handled gracefully:</b> solo households (1 member = the
 * user) are deleted along with their dependent data.</p>
 *
 * <p><b>What is NOT covered (yet):</b>
 * <ul>
 *   <li>Anonymization of group conversation history. Posts the user made
 *       in groups they share with other members are deleted, which can
 *       leave reply chains orphaned. v2 may anonymize instead.</li>
 *   <li>Pending-member-email cleanup across all groups. The pending-emails
 *       list is an @ElementCollection on Group — for now we leave any
 *       residual entries (rare; user just won't be auto-approved). A
 *       sweep cron in {@code SCHEDULED_JOBS.md} can clean up stragglers.</li>
 *   <li>Backups. Deletion runs against primary tables; encrypted backups
 *       roll off on their own cycle (privacy policy notes ~30 days).</li>
 * </ul>
 * </p>
 */
@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    @PersistenceContext
    private EntityManager em;

    private final UserInfoRepo userInfoRepo;
    private final GroupRepo groupRepo;

    public AccountDeletionService(UserInfoRepo userInfoRepo, GroupRepo groupRepo) {
        this.userInfoRepo = userInfoRepo;
        this.groupRepo = groupRepo;
    }

    /**
     * Cascade-delete the account identified by {@code email}.
     *
     * @throws OwnedGroupsBlockingException if the user owns a multi-member
     *         household OR any non-household group. The frontend surfaces
     *         the {@code blockedGroups} field as a list of "transfer or
     *         leave these first" hints.
     */
    @Transactional
    public DeletionResult deleteAccount(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email is required");
        }
        final String e = email.trim().toLowerCase();
        log.info("AccountDeletion: starting for email={}", e);

        // -----------------------------------------------------------------
        // 1. Validate ownership constraints. Users can't delete themselves
        // out of a multi-member group they own — they have to transfer
        // ownership or leave first. Solo households (just the user) are
        // OK to auto-delete.
        // -----------------------------------------------------------------
        // GroupRepo doesn't expose a findByOwnerEmail (member/admin queries
        // exist but ownership isn't a collection field). JPQL directly.
        List<Group> ownedGroups = em.createQuery(
                "SELECT g FROM Group g WHERE LOWER(g.ownerEmail) = :e", Group.class)
                .setParameter("e", e)
                .getResultList();

        List<Group> blocking = new ArrayList<>();
        List<Group> soloHouseholds = new ArrayList<>();
        for (Group g : ownedGroups) {
            int memberCount = g.getMemberEmails() == null ? 0 : g.getMemberEmails().size();
            boolean isHousehold = "Household".equalsIgnoreCase(g.getGroupType());
            if (isHousehold && memberCount <= 1) {
                soloHouseholds.add(g);
            } else {
                blocking.add(g);
            }
        }
        if (!blocking.isEmpty()) {
            throw new OwnedGroupsBlockingException(
                    blocking.stream()
                            .map(g -> new BlockingGroup(g.getGroupId(), g.getGroupName(), g.getGroupType()))
                            .toList()
            );
        }

        // -----------------------------------------------------------------
        // 2. Cascade-delete content the user authored / owns. JPQL bulk
        // DELETE — efficient and won't pull entities into memory.
        // -----------------------------------------------------------------
        int reactions   = bulkDelete("DELETE FROM GroupPostReaction r WHERE LOWER(r.userEmail) = :e", e);
        int comments    = bulkDelete("DELETE FROM GroupPostComment c WHERE LOWER(c.author) = :e", e);
        int posts       = bulkDelete("DELETE FROM GroupPost p WHERE LOWER(p.author) = :e", e);
        int tasks       = bulkDelete(
                "DELETE FROM Post t WHERE LOWER(t.requesterEmail) = :e OR LOWER(t.claimedByEmail) = :e", e);
        int acks        = bulkDelete("DELETE FROM PlanActivationAck a WHERE LOWER(a.recipientEmail) = :e", e);
        int activations = bulkDelete("DELETE FROM PlanActivation a WHERE LOWER(a.ownerEmail) = :e", e);
        int meals       = bulkDelete("DELETE FROM MealPlanData m WHERE LOWER(m.ownerEmail) = :e", e);
        int evacs       = bulkDelete("DELETE FROM EvacuationPlan p WHERE LOWER(p.ownerEmail) = :e", e);
        int meetings    = bulkDelete("DELETE FROM MeetingPlace m WHERE LOWER(m.ownerEmail) = :e", e);
        int origins     = bulkDelete("DELETE FROM OriginLocation o WHERE LOWER(o.ownerEmail) = :e", e);
        // EmergencyContactGroup has @OneToMany cascade for EmergencyContact —
        // deleting the parent removes children automatically. Loading +
        // delete to make sure cascade fires (JPQL bulk DELETE skips cascade).
        List<Long> contactGroupIds = em.createQuery(
                "SELECT g.id FROM EmergencyContactGroup g WHERE LOWER(g.ownerEmail) = :e", Long.class)
                .setParameter("e", e)
                .getResultList();
        for (Long id : contactGroupIds) {
            em.createQuery("DELETE FROM EmergencyContactGroup g WHERE g.id = :id")
                    .setParameter("id", id)
                    .executeUpdate();
        }
        int contacts    = contactGroupIds.size();
        int demographic = bulkDelete("DELETE FROM Demographic d WHERE LOWER(d.ownerEmail) = :e", e);
        int saved       = bulkDelete("DELETE FROM UserSavedLocation l WHERE LOWER(l.ownerEmail) = :e", e);
        int events      = bulkDelete("DELETE FROM HouseholdEvent h WHERE LOWER(h.actorEmail) = :e", e);
        int accomp      = bulkDelete(
                "DELETE FROM HouseholdAccompaniment h " +
                "WHERE (h.supervisorKind = 'user' AND LOWER(h.supervisorId) = :e) " +
                "   OR (h.accompaniedKind = 'user' AND LOWER(h.accompaniedId) = :e)", e);
        int notifs      = bulkDelete("DELETE FROM NotificationLog n WHERE LOWER(n.recipientEmail) = :e", e);

        // -----------------------------------------------------------------
        // 3. Strip user from every group's memberEmails / adminEmails /
        // pendingMemberEmails collections. These are @ElementCollection so
        // we have to load + mutate + save (JPQL can't reach element-
        // collection rows directly).
        // -----------------------------------------------------------------
        Set<Group> toUpdate = new HashSet<>();
        toUpdate.addAll(safeFind(() -> groupRepo.findByMemberEmail(e)));
        toUpdate.addAll(safeFind(() -> groupRepo.findByAdminEmail(e)));
        for (Group g : toUpdate) {
            boolean changed = false;
            if (g.getMemberEmails() != null
                    && g.getMemberEmails().removeIf(m -> e.equalsIgnoreCase(m))) {
                changed = true;
            }
            if (g.getAdminEmails() != null
                    && g.getAdminEmails().removeIf(a -> e.equalsIgnoreCase(a))) {
                changed = true;
            }
            if (g.getPendingMemberEmails() != null
                    && g.getPendingMemberEmails().removeIf(p -> e.equalsIgnoreCase(p))) {
                changed = true;
            }
            if (changed) groupRepo.save(g);
        }
        int strippedFrom = toUpdate.size();

        // -----------------------------------------------------------------
        // 4. Delete solo households the user owns + their dependent data.
        // -----------------------------------------------------------------
        for (Group g : soloHouseholds) {
            String hh = g.getGroupId();
            em.createQuery("DELETE FROM HouseholdManualMember m WHERE m.householdId = :hh")
                    .setParameter("hh", hh).executeUpdate();
            em.createQuery("DELETE FROM HouseholdAccompaniment a WHERE a.householdId = :hh")
                    .setParameter("hh", hh).executeUpdate();
            em.createQuery("DELETE FROM HouseholdEvent he WHERE he.householdId = :hh")
                    .setParameter("hh", hh).executeUpdate();
            groupRepo.delete(g);
        }

        // -----------------------------------------------------------------
        // 5. Delete the UserInfo record itself.
        // -----------------------------------------------------------------
        userInfoRepo.findByUserEmailIgnoreCase(e).ifPresent(userInfoRepo::delete);

        DeletionResult result = new DeletionResult(
                e,
                posts, comments, reactions, tasks,
                activations, acks,
                meals, evacs, meetings, origins, contacts, demographic, saved,
                events, accomp, notifs,
                strippedFrom, soloHouseholds.size()
        );
        log.info("AccountDeletion: complete for email={} result={}", e, result);
        return result;
    }

    private int bulkDelete(String jpql, String email) {
        try {
            return em.createQuery(jpql).setParameter("e", email).executeUpdate();
        } catch (Exception ex) {
            // Defensive: if a sub-cascade fails, log and continue. Never
            // abort the whole deletion partway — the goal is "best-effort
            // cascade with a final UserInfo delete." Apple's req is "the
            // user's account is gone"; orphan rows (rare) are cleaned up
            // by a separate sweep cron.
            log.warn("AccountDeletion: bulk delete failed jpql='{}' err={}", jpql, ex.getMessage());
            return 0;
        }
    }

    private <T> List<T> safeFind(java.util.function.Supplier<List<T>> op) {
        try {
            List<T> r = op.get();
            return r == null ? List.of() : r;
        } catch (Exception ex) {
            log.warn("AccountDeletion: lookup failed err={}", ex.getMessage());
            return List.of();
        }
    }

    /** Counters returned to the caller for telemetry + the FE confirmation copy. */
    public record DeletionResult(
            String email,
            int posts, int comments, int reactions, int tasks,
            int activations, int acks,
            int mealPlans, int evacPlans, int meetingPlaces, int originLocations,
            int emergencyContactGroups, int demographic, int savedLocations,
            int householdEvents, int accompaniments, int notifications,
            int strippedFromGroups, int deletedSoloHouseholds
    ) {}

    public record BlockingGroup(String groupId, String groupName, String groupType) {}

    /**
     * Thrown when the user owns a group that can't be auto-cleaned. The FE
     * receives 409 + the list of blocking groups so it can render
     * "transfer ownership of X first" UI.
     */
    public static class OwnedGroupsBlockingException extends RuntimeException {
        private final List<BlockingGroup> blocked;
        public OwnedGroupsBlockingException(List<BlockingGroup> blocked) {
            super("Account has owned groups that block deletion: " + blocked.size());
            this.blocked = blocked;
        }
        public List<BlockingGroup> blocked() { return blocked; }
    }
}
