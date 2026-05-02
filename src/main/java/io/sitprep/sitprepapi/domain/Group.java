package io.sitprep.sitprepapi.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "groups")
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Group {

    @Id
    @Column(name = "group_id", unique = true, nullable = false)
    private String groupId;  // UUID as primary ID (VARCHAR)

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "group_admin_emails", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "admin_email")
    private List<String> adminEmails;

    private String alert;
    /**
     * When alert mode is active, the kind of hazard the admin selected
     * (one of: "hurricane", "wildfire", "earthquake", "flood", "blizzard",
     * "other"). Lowercase, free-form for forward compat. Null when alert
     * is calm OR when the admin didn't specify a type. Drives the
     * contextual guide pin on /home + /ask (per docs/ECOSYSTEM_INTEGRATION.md
     * step 7) AND the auto-post template selection (per
     * docs/ALERTS_INTEGRATION.md auto-post dispatcher).
     */
    @Column(name = "active_hazard_type")
    private String activeHazardType;

    /**
     * Timestamp the alert most recently flipped to {@code "Active"}. Used by
     * {@code GroupAlertDecayService} to find groups whose admin forgot to
     * clear the alert and auto-resolve them after a threshold. Null when
     * the alert has never been activated, or was cleared by an admin.
     *
     * <p>Set in {@code GroupService.updateGroupFields} on the
     * {@code alertBecameActive} branch, cleared on {@code alertBecameInactive}.
     * Pre-existing Active alerts at deploy time will have this null and
     * therefore won't auto-decay until they're flipped manually once —
     * acceptable trade-off vs. a backfill migration.</p>
     */
    @Column(name = "alert_activated_at")
    private Instant alertActivatedAt;

    /**
     * Count of check-in reminders that have fired since the alert went
     * Active. Used by {@code GroupCheckInReminderService} to dedupe
     * its scheduled fires — the service computes "which reminder slot
     * does the current elapsed time match?" and fires only when the
     * counter is below that slot's index. Reset to 0 every time the
     * alert flips to Active, and on the auto-decay flip to Inactive.
     *
     * <p>Slots (0-indexed): 30min / 4h / 12h / 24h / 36h. After the
     * 5th reminder fires, the next service tick sees the alert
     * approach the 48h decay threshold and {@code GroupAlertDecayService}
     * takes over, sending the "Continue check-in?" notification.</p>
     */
    @Column(name = "checkin_reminders_fired")
    private Integer checkInRemindersFired;

    private Instant createdAt;
    private String description;
    private String groupCode;
    private String groupName;
    private String groupType;
    private String lastUpdatedBy;
    private Integer memberCount;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "group_member_emails", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "member_email")
    private List<String> memberEmails;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "group_pending_member_emails", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "pending_member_email")
    private List<String> pendingMemberEmails;

    private String privacy;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "group_sub_group_ids", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "sub_group_id")
    private List<String> subGroupIDs;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "group_parent_group_ids", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "parent_group_id")
    private List<String> parentGroupIDs;

    private Instant updatedAt;
    private String address;
    private String longitude;
    private String latitude;
    private String zipCode;
    private String ownerName;
    private String ownerEmail;

}