package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.Group;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GroupRepo extends JpaRepository<Group, String> {

    @Query("SELECT g FROM Group g JOIN g.adminEmails a WHERE LOWER(a) = LOWER(:email)")
    List<Group> findByAdminEmail(@Param("email") String email);

    // Authoritative lookup for "groups this user belongs to" — reads the
    // Group side (member_emails) directly rather than relying on the
    // denormalized UserInfo.joinedGroupIDs cache. Case-insensitive.
    @Query("SELECT g FROM Group g JOIN g.memberEmails m WHERE LOWER(m) = LOWER(:email)")
    List<Group> findByMemberEmail(@Param("email") String email);

    // ✅ For UUID-based lookup by public groupId
    Optional<Group> findByGroupId(String groupId);

    /**
     * Case-insensitive uniqueness check for the group-create flows.
     * Replaces the FE pattern of pulling the entire groups table on
     * every keystroke and filtering in memory. Returns true when at
     * least one group already uses the supplied name / code.
     */
    boolean existsByGroupNameIgnoreCase(String groupName);

    boolean existsByGroupCodeIgnoreCase(String groupCode);

    // ✅ Optional: For findByAdminEmailsContaining (alternative to @Query)
    List<Group> findByAdminEmailsContaining(String adminEmail);

    @Query("SELECT g FROM Group g LEFT JOIN FETCH g.memberEmails WHERE g.groupId = :groupId")
    Optional<Group> findByGroupIdWithMembers(@Param("groupId") String groupId);

    /**
     * Groups whose alert is currently {@code "Active"} AND whose
     * {@code alertActivatedAt} is older than {@code cutoff}. Used by
     * {@code GroupAlertDecayService} to find the alerts an admin forgot
     * to clear. Null {@code alertActivatedAt} is excluded — those are
     * pre-deploy backlog and require a manual flip to start being
     * auto-decayable (avoids surprise mass-clears on rollout).
     */
    @Query(
        "SELECT g FROM Group g " +
        "WHERE LOWER(g.alert) = 'active' " +
        "AND g.alertActivatedAt IS NOT NULL " +
        "AND g.alertActivatedAt < :cutoff " +
        "ORDER BY g.alertActivatedAt ASC"
    )
    List<Group> findStaleActiveAlerts(@Param("cutoff") java.time.Instant cutoff,
                                      org.springframework.data.domain.Pageable page);

    /**
     * Groups with an active alert that may be due for one of their
     * 5 in-window reminders. The reminder service receives this
     * list and decides per-group which (if any) reminder slot to
     * fire based on elapsed time vs. {@code checkInRemindersFired}.
     *
     * <p>We don't filter by elapsed-time here because the slots are
     * compared in Java (cleaner than five OR clauses in JPQL).
     * Only filter is "alert is active and we have a timestamp to
     * measure from"; ordering pulls the oldest first so a backlog
     * after downtime drains FIFO.</p>
     */
    @Query(
        "SELECT g FROM Group g " +
        "WHERE LOWER(g.alert) = 'active' " +
        "AND g.alertActivatedAt IS NOT NULL " +
        "ORDER BY g.alertActivatedAt ASC"
    )
    List<Group> findActiveAlertsForReminderSweep(org.springframework.data.domain.Pageable page);
}
