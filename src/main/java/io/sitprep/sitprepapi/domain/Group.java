package io.sitprep.sitprepapi.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Organization plan tier — the enum name of
     * {@link io.sitprep.sitprepapi.constant.PlanTier}
     * ({@code FREE} / {@code GROUP} / {@code BUSINESS} / {@code AGENCY}
     * / {@code PREMIUM_AGENCY}). Phase 4 of docs/BUSINESS_MODEL.md.
     *
     * <p>Null on legacy rows — always read it through
     * {@code PlanTier.fromWire(...)}, which maps null/blank/unknown to
     * {@code FREE}. Drives soft capability gating; see
     * {@link io.sitprep.sitprepapi.constant.PlanCapability}.</p>
     */
    @Column(name = "plan_tier")
    private String planTier;

    /**
     * Custom organization logo — a public image URL (Cloudflare R2).
     * Phase 4 of docs/BUSINESS_MODEL.md, the "co-branded page"
     * capability. When set, the group page renders this in place of
     * the default group-type emblem; null falls back to the emblem.
     * Uploaded via {@code POST /api/images}, then PATCHed here.
     */
    @Column(name = "logo_image_url", length = 1024)
    private String logoImageUrl;

    /**
     * Stripe billing identifiers — Phase 4 of docs/BUSINESS_MODEL.md.
     * {@code stripeCustomerId} is the group's Stripe Customer (the
     * billing account); {@code stripeSubscriptionId} the active org-
     * plan subscription; {@code subscriptionStatus} mirrors Stripe's
     * subscription status (active / trialing / past_due / canceled).
     * All null until the owner subscribes via Stripe Checkout — the
     * billing webhook keeps {@code planTier} in sync with the
     * subscription lifecycle.
     */
    @Column(name = "stripe_customer_id")
    private String stripeCustomerId;

    @Column(name = "stripe_subscription_id")
    private String stripeSubscriptionId;

    @Column(name = "subscription_status")
    private String subscriptionStatus;

    /**
     * Timestamp the household plan was most recently <i>confirmed as current</i>
     * — distinct from {@link #updatedAt}, which tracks any edit to the Group
     * row (name change, member add, etc.). §3 of
     * {@code docs/HOME_HOUSEHOLD_BEHAVIORAL_DESIGN.md}: surfaces calibrated
     * loss aversion ("confirmed 4 months ago — quick refresh?") on stale
     * plans without forcing the user to re-edit every section. Set by
     * {@code POST /api/households/{id}/plan/confirm}.
     *
     * <p>Null on legacy rows — the FE treats null as "not yet confirmed"
     * and falls back to the standard plan sub-copy. A user can bring a
     * legacy plan into the freshness pattern by tapping "Mark confirmed"
     * once on the Plan tab (FE Round 2). Only relevant for groups with
     * {@code groupType = "Household"}; null is safe on every other row.</p>
     */
    @Column(name = "plan_last_confirmed_at")
    private Instant planLastConfirmedAt;

    /**
     * Per-household weekly preparedness challenge completion log. Keys are
     * ISO-week-year strings ("2026-W22"), values are {@code true} when the
     * household has marked that week's curated drill done. Surfaces on
     * {@link io.sitprep.sitprepapi.dto.MeDto.HouseholdDto#challengeProgress()}
     * and is written by
     * {@code POST /api/households/{id}/challenges/{weekKey}/complete}.
     *
     * <p>Round-1 storage uses the same {@code @ElementCollection} +
     * {@code @MapKeyColumn} pattern as {@code UserInfo.groupLocationSharing}
     * (lives at {@code group_challenge_progress} side-table) — boring,
     * indexable, no JSON column / Hibernate JdbcTypeCode dependency, plays
     * fine with the existing schema.gen flow.</p>
     *
     * <p>FE behavior: empty / absent map reads as "nothing done yet"; the
     * FE legacy {@code meCache} bucket continues to work for offline
     * writes and as a fallback for legacy users until they touch the
     * surface once.</p>
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "group_challenge_progress",
            joinColumns = @JoinColumn(name = "group_id")
    )
    @MapKeyColumn(name = "week_key", length = 16)
    @Column(name = "completed")
    private Map<String, Boolean> challengeProgress = new HashMap<>();

}
