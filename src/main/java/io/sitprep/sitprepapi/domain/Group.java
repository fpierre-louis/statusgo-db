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

    /**
     * Optimistic-locking token — audit P1-6. JPA increments on every flush;
     * concurrent updates that race on a stale read fail with
     * {@code OptimisticLockingFailureException}, which the global handler
     * surfaces as HTTP 409 {@code STALE_WRITE}.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // Reverted to EAGER for launch (post-audit re-evaluation): P2-7 flipped this
    // to LAZY but ~30 call sites across GroupService / GroupRole / AccountDeletion /
    // HouseholdRitualScheduler / GroupCheckInReminderService / PlanActivationService
    // walk these collections — many outside an open session (scheduled tasks, async
    // dispatch, post-tx event handlers). Auditing each site for tx safety is
    // post-launch hardening; EAGER preserves the pre-audit behavior and avoids
    // LazyInitializationException in production. Cost: ~3 small side-table joins
    // per Group load. Acceptable for launch volume.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "group_admin_emails", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "admin_email")
    @OrderColumn(name = "ord")
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

    // EAGER (see adminEmails comment) — launch-safety revert of P2-7.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "group_member_emails", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "member_email")
    @OrderColumn(name = "ord")
    private List<String> memberEmails;

    // EAGER (see adminEmails comment) — launch-safety revert of P2-7.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "group_pending_member_emails", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "pending_member_email")
    @OrderColumn(name = "ord")
    private List<String> pendingMemberEmails;

    private String privacy;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "group_sub_group_ids", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "sub_group_id")
    @OrderColumn(name = "ord")
    private List<String> subGroupIDs;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "group_parent_group_ids", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "parent_group_id")
    @OrderColumn(name = "ord")
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
     * Business-only profile fields (Phase 5 Slice A). Populated by the
     * group-creation wizard when {@code groupType == "Business"}; null for
     * every other type. Drive the BusinessAboutCard on the group page —
     * a differentiated profile unlocked purely by the type choice, NOT by
     * verification (verification/claim is agency-only). Additive, nullable.
     */
    @Column(name = "business_category", length = 64)
    private String businessCategory;

    @Column(name = "website_url", length = 512)
    private String websiteUrl;

    /**
     * Phase 5 jurisdiction model (Slice C). For a verified agency group: the
     * set of US zips the agency is authorized to geo-target. Set during
     * super-admin provisioning; an agency alert may only target zips IN this
     * set. Empty/null for non-agency groups. Explicit @CollectionTable +
     * join column to match every other Group collection (and dodge the
     * Hibernate physical-table-name collection-naming trap).
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "group_jurisdiction_zips", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "zip", length = 12)
    private List<String> jurisdictionZips;

    /** city | county | state | public-safety | utility | other — display + gating. */
    @Column(name = "jurisdiction_type", length = 24)
    private String jurisdictionType;

    /** Non-agency org service radius (miles) for community-feed reach. Null default. */
    @Column(name = "service_area_radius")
    private Double serviceAreaRadius;

    @Column(name = "jurisdiction_lat")
    private Double jurisdictionLat;

    @Column(name = "jurisdiction_lng")
    private Double jurisdictionLng;

    /** Authorized agency targeting radius in miles. Null until provisioned. */
    @Column(name = "jurisdiction_radius_mi")
    private Double jurisdictionRadiusMiles;

    @Column(name = "agency_authorized", columnDefinition = "boolean NOT NULL DEFAULT false")
    private boolean agencyAuthorized = false;

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
     * Admin-managed, time-boxed access bypass for pilots, gifts, demos,
     * and promotions. Stripe remains the source of truth for paid
     * subscriptions; these fields describe an explicit console override.
     */
    @Column(name = "subscription_override_tier", length = 64)
    private String subscriptionOverrideTier;

    @Column(name = "subscription_override_expires_at")
    private Instant subscriptionOverrideExpiresAt;

    @Column(name = "subscription_override_reason", length = 500)
    private String subscriptionOverrideReason;

    @Column(name = "subscription_override_by", length = 320)
    private String subscriptionOverrideBy;

    @Column(name = "subscription_override_at")
    private Instant subscriptionOverrideAt;

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
