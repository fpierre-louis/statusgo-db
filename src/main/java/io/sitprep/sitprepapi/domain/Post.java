package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A task / request-for-help. Three scopes all share this one entity:
 *
 * <ul>
 *   <li><b>Group task</b> — {@code groupId != null && claimedByGroupId == null}.
 *       Scoped to that group. The traditional work-order flow.</li>
 *   <li><b>Community / personal task</b> — {@code groupId == null}. Community
 *       kinds surface to anyone in the {@code latitude/longitude} + radius set
 *       by the viewer; personal-scope kinds ({@link io.sitprep.sitprepapi.constant.PostKind#isPersonalScope})
 *       stay private to their requester. Used when an individual asks for help
 *       and any nearby group can claim.</li>
 *   <li><b>Group-claimed community task</b> — community-scope task that a group
 *       leader has claimed on behalf of their group. Both the requester and
 *       the claimer-group's members see live status.</li>
 * </ul>
 *
 * <p><b>Visibility is not described here — it is enforced by
 * {@link io.sitprep.sitprepapi.service.PostReadAuthorizer#canRead}, which is
 * the authoritative rule.</b> A group-scoped row is readable by a member of
 * its group, by its requester, by members of a group that claimed it, and by
 * anyone assigned to it. Read that method rather than this list; the two
 * drifting apart is precisely how this entity got into trouble.</p>
 *
 * <p>Until 2026-08-12 this doc asserted that a group task was "visible only to
 * that group's members". Nothing implemented that. Every read path returned
 * whatever the query produced, so any authenticated caller could fetch any row
 * by id — and {@code id} is a dense integer sequence. The sentence was
 * accurate as a statement of intent and false as a statement of behavior for
 * as long as the entity existed. See
 * {@code docs/audits/post-by-id-authorization.md}, and T-31 in
 * {@code SYSTEM_TRAPS_AND_PATTERNS.md}: a comment describes intent; only the
 * code describes behavior. If you change the rule, change
 * {@code PostReadAuthorizer} and let this paragraph point at it — do not
 * restate the rule here where it cannot be executed.</p>
 *
 * <p>{@code zipBucket} (first 3 chars of postcode) is a cheap pre-filter for
 * the community-discover JPQL — by-radius scans hit only rows matching the
 * viewer's bucket before the in-memory Haversine pass.</p>
 */
@Entity
@Getter
@Setter
@Table(
        name = "task",
        indexes = {
                @Index(name = "idx_task_group_status", columnList = "group_id,status"),
                @Index(name = "idx_task_zip_status", columnList = "zip_bucket,status"),
                @Index(name = "idx_task_requester", columnList = "requester_email"),
                @Index(name = "idx_task_claimer", columnList = "claimed_by_email"),
                @Index(name = "idx_task_tagged_agency", columnList = "tagged_agency_group_id,civic_status")
        }
)
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null = personal/community-scope; non-null = bound to that group. */
    @Column(name = "group_id")
    private String groupId;

    @Column(name = "requester_email", nullable = false)
    private String requesterEmail;

    /**
     * When set, this post is attributed to a group rather than to the
     * individual {@link #requesterEmail}. Used when an admin of an org
     * group (school, business, neighborhood, church) wants to speak on
     * behalf of the group in the community feed — the FE renders the
     * group's emblem + name as the author header instead of the
     * individual admin's identity.
     *
     * <p>Distinct from {@link #claimedByGroupId} — that's "a group
     * took on this task," whereas this field is "this post IS the
     * group speaking." Both can be set independently.</p>
     *
     * <p>Validation: when set on create, the service layer requires
     * {@link #requesterEmail} to be in the target group's
     * {@code adminEmails} or to be the owner. Otherwise the request is
     * rejected with 400 — we don't silently strip the attribution
     * because that would let a non-admin appear to author as a group
     * by accident on a misconfigured client.</p>
     */
    @Column(name = "authored_as_group_id", length = 64)
    private String authoredAsGroupId;

    /** The group that claimed this task (community → claimed). Null while open. */
    @Column(name = "claimed_by_group_id")
    private String claimedByGroupId;

    /** The specific user inside the claimer group who took it on. Null while open. */
    @Column(name = "claimed_by_email")
    private String claimedByEmail;

    /**
     * Email of the member this task is assigned to — push assignment by
     * a group admin. Distinct from {@link #claimedByEmail}: claim is
     * pull (a member takes an open task), assignment is push (an admin
     * gives a specific member a task). Null when unassigned. Phase 3 of
     * docs/BUSINESS_MODEL.md — "tasks become operational".
     */
    @Column(name = "assignee_email")
    private String assigneeEmail;

    /** Group admin who made the current assignment; null when unassigned. */
    @Column(name = "assigned_by_email")
    private String assignedByEmail;

    /** When the current assignment was made; null when unassigned. */
    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PostStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PostPriority priority;

    /**
     * Post kind — the row's role in the community feed. Per
     * {@code docs/MARKETPLACE_AND_FEED_CALM.md} "Feed: post types
     * beyond Asks", the same {@code Post} entity now carries the full
     * vocabulary so the feed surface can render mixed content via one
     * pipeline.
     *
     * <p><b>Vocabulary</b> (lowercase, free-form for forward compat;
     * service-layer validates against an authorized set):</p>
     * <ul>
     *   <li>{@code ask} — request for help (legacy default)</li>
     *   <li>{@code offer} — neighbor offering to help / lend</li>
     *   <li>{@code tip} — short prep tip / lessons-learned</li>
     *   <li>{@code recommendation} — vouched-for local services</li>
     *   <li>{@code lost-found} — pets, items</li>
     *   <li>{@code alert-update} — neighbor situational awareness during a declared incident</li>
     *   <li>{@code blog-promo} — surfaces a SitPrep blog article in-feed</li>
     *   <li>{@code marketplace} — for-sale / free / service listings (the eventual Marketplace tab)</li>
     * </ul>
     *
     * <p><b>Default {@code "ask"}</b> on legacy rows + on the
     * existing FE composer flow (no FE change needed for the schema
     * landing). New kinds unlock as the FE composer expands per spec
     * build order.</p>
     */
    @Column(name = "kind", nullable = false, length = 32,
            columnDefinition = "varchar(32) NOT NULL DEFAULT 'ask'")
    private String kind = "ask";

    /**
     * Human title. Required for kinds where the user actually enters a
     * title separate from the body (ask, marketplace, recommendation,
     * lost-found, alert-update). Null for kinds where the body IS the
     * post (post / tip) — synthesizing a title from the first line of
     * the description for these caused the bold-title-then-same-text-
     * in-body visual duplicate that prompted the 2026-05-04 cleanup.
     * Service-layer enforces the per-kind requirement; this column
     * stays nullable to allow kinds with no title.
     */
    @Column(length = 200)
    private String title;

    @Column(length = 4096)
    private String description;

    /** For radius filtering on community-scope tasks. Null otherwise. */
    private Double latitude;
    private Double longitude;

    /** First 3 chars of postcode — see class doc. */
    @Column(name = "zip_bucket", length = 8)
    private String zipBucket;

    /**
     * Reverse-geocoded place label (neighborhood when available, else
     * city). Populated at create time from {@link
     * io.sitprep.sitprepapi.service.NominatimGeocodeService} alongside
     * {@link #zipBucket} so the FE can render a Nextdoor-style
     * "{neighborhood} · {time}" subtitle without a per-row geocode
     * round trip. Null when the post is geo-less or the lookup failed
     * — the FE collapses gracefully to time-only.
     */
    @Column(name = "place_label", length = 128)
    private String placeLabel;

    @Column(name = "due_at")
    private Instant dueAt;

    /**
     * When a due-date reminder was sent for this row. Used by
     * {@code PersonalTaskReminderService} to fire a reminder exactly
     * once per task — the daily sweep filters out rows where this is
     * already set. Null until the reminder fires (the common state);
     * null on every non-task row.
     *
     * <p>Phase 1 of BUSINESS_MODEL.md — supply reminders. A personal
     * task created from a template with a refresh cadence (water every
     * 6 months, batteries every 12) carries a future {@code dueAt};
     * when that date passes, the sweep notifies the owner and stamps
     * this field so the reminder doesn't repeat daily.</p>
     */
    @Column(name = "reminder_sent_at")
    private Instant reminderSentAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** R2 image keys (post/<uuid>.jpg style). Receipts, damage photos, etc. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "task_image_keys", joinColumns = @JoinColumn(name = "task_id"))
    @Column(name = "image_key")
    @OrderColumn(name = "ord")
    private List<String> imageKeys = new ArrayList<>();

    /**
     * USER-AUTHORED topic tags, and strictly that as of V62.
     *
     * <p>This column used to carry three unrelated channels at once —
     * provenance, hazard, and topic — because it was the only untyped string
     * set on the entity. Machine writers no longer touch it: the dispatcher
     * writes {@link #sourceKey} and {@link #hazardTags} instead.</p>
     *
     * <p><b>Do not put a machine-written value here again.</b> If a new writer
     * needs to classify a post, it needs its own typed channel — that is the
     * whole lesson of the 12,126 rows V62 moved out.</p>
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "task_tags", joinColumns = @JoinColumn(name = "task_id"))
    @Column(name = "tag")
    private Set<String> tags = new HashSet<>();

    /**
     * Hazard classification — one shared vocabulary ({@link io.sitprep.sitprepapi.constant.HazardType}).
     *
     * <p><b>Split out of {@link #tags} in V62.</b> Before that, `task_tags` was
     * three channels sharing one untyped column: provenance
     * ({@code system-alert}, {@code nws}, {@code usgs}), hazard
     * ({@code flood}, {@code tornado}, {@code earthquake}) and — in exactly one
     * row — a namespaced {@code pillar:supplies} that someone reached for
     * BECAUSE the column had no namespace. That single row was the tell.</p>
     *
     * <p>A collection rather than a scalar even though every backfilled row
     * carries exactly one: an NWS alert can legitimately be both a flood and a
     * wind event, and modelling the current data instead of the domain is how
     * you buy a second migration.</p>
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "task_hazard_tags", joinColumns = @JoinColumn(name = "task_id"))
    @Column(name = "hazard", length = 32)
    private Set<String> hazardTags = new HashSet<>();

    /**
     * Where this post came from — {@code nws} | {@code usgs} | {@code agency} |
     * {@code user}. Null on rows predating V62 that no backfill could classify.
     *
     * <p><b>Scalar, and the data says so.</b> Every one of the 12,126 machine
     * posts in production carries exactly ONE source tag; not a single row has
     * two. Provenance was multi-valued only because it was sharing a
     * {@code Set<String>} with hazard, which genuinely is.</p>
     */
    @Column(name = "source_key", length = 24)
    private String sourceKey;

    /**
     * Repost / quote-post pointer — the post this row quotes. Wired to
     * {@code PostService.withParentPosts} (folds a compact quote-card preview)
     * and the create-path blank-description bypass. NOTE: despite the legacy
     * "sub-task" column name, this is the REPOST link, NOT the bundles link.
     * Bundles/projects use {@link #projectId} (V51) — a separate column.
     */
    @Column(name = "parent_task_id")
    private Long parentPostId;

    /**
     * Bundles / projects (V51) — the container this task belongs to, or null
     * for a standalone task. Points at a {@code kind="project"} Post row that
     * groups several child tasks for one recipient/home. Distinct from
     * {@link #parentPostId} (the repost pointer). Modeled as a plain scalar
     * {@code Long} (not a {@code @ManyToOne}) — same as {@code parentPostId} —
     * so Hibernate treats it as a column and never tries to manage the
     * association; the DB self-FK is {@code ON DELETE SET NULL} (deleting a
     * project detaches its children to standalone, never cascade-deletes them).
     */
    @Column(name = "project_id")
    private Long projectId;

    // -----------------------------------------------------------------
    // Sponsored content fields — docs/SPONSORED_AND_ALERT_MODE.md
    // build-order step 3. v1 sponsorship is admin-flagged (no self-
    // serve creation flow yet); these columns let PostService.discover-
    // Community apply mode-aware suppression rules per spec:
    //
    //   • mode=calm        → sponsored shown alongside organic
    //   • mode=attention   → sponsored hidden UNLESS crisisRelevant
    //   • mode=alert       → sponsored hidden UNLESS crisisRelevant
    //                        (and rendered in a "Verified service" lane)
    //   • mode=crisis      → ALL sponsored hidden, regardless of flag
    //
    // crisisRelevant marks asks/listings that are useful DURING a
    // crisis (tree removal after a windstorm, water restoration,
    // generator repair). Insurance comparison sites, weight-loss
    // apps → crisisRelevant=false → suppress from the moment a cell
    // enters attention mode.
    // -----------------------------------------------------------------

    @Column(name = "sponsored", nullable = false,
            columnDefinition = "boolean NOT NULL DEFAULT false")
    private boolean sponsored = false;

    @Column(name = "crisis_relevant", nullable = false,
            columnDefinition = "boolean NOT NULL DEFAULT false")
    private boolean crisisRelevant = false;

    /** When the sponsored placement expires. Null when not sponsored. */
    @Column(name = "sponsored_until")
    private Instant sponsoredUntil;

    /**
     * Billing handle / sponsor identifier. Free-form for v1 since the
     * self-serve creation flow doesn't exist yet — admins typing a
     * stable identifier per sponsor (e.g. "redroof-roofing-atl").
     */
    @Column(name = "sponsored_by", length = 128)
    private String sponsoredBy;

    // -----------------------------------------------------------------
    // Marketplace fields — per docs/MARKETPLACE_AND_FEED_CALM.md
    // "Data model sketch". Only meaningful when kind="marketplace";
    // null/false on every other kind. SitPrep does NOT process
    // payments — these fields are pure metadata. Buyer pays seller
    // off-app via the seller's chosen platform (Venmo / CashApp /
    // Zelle / Apple Pay / Google Pay / PayPal / Cash on pickup).
    // Status reuse: OPEN = available, DONE = sold, CANCELLED =
    // withdrawn. No new enum value to keep the schema simple.
    // -----------------------------------------------------------------

    /**
     * Listing price in USD. Null when not applicable (asks, tips,
     * etc.) or when {@link #isFree} is true. The seller can edit this
     * up until first claim; afterwards locked.
     */
    @Column(name = "price", precision = 10, scale = 2)
    private java.math.BigDecimal price;

    /**
     * Marketplace "free" listings — community gift signal. Marketplace
     * sort boosts these so they surface ahead of priced items at
     * equal proximity. Mutually exclusive with {@link #price} (set one
     * or the other; setting both is a service-layer validation error).
     */
    @Column(name = "is_free", nullable = false,
            columnDefinition = "boolean NOT NULL DEFAULT false")
    private boolean isFree = false;

    /**
     * Off-app payment handles for the listing. Per
     * {@code docs/MARKETPLACE_AND_FEED_CALM.md} "Off-app payment
     * platform routing" — SitPrep doesn't process payments; the
     * seller attaches handles and the buyer settles directly.
     *
     * <p>Stored as JSON text rather than columns so adding a new
     * platform (e.g. a future "Bitcoin" or regional payment app) is
     * a FE picker change with zero schema migration. Shape:</p>
     *
     * <pre>
     * {
     *   "venmo":        "@dione",         // optional handle string
     *   "cashApp":      "$dione",         // optional handle string
     *   "zelle":        "dione@x.com",    // optional handle/phone
     *   "paypal":       "dione@x.com",    // optional handle/email
     *   "applePay":     true,             // optional accept flag
     *   "googlePay":    true,             // optional accept flag
     *   "cashOnPickup": true              // optional accept flag
     * }
     * </pre>
     *
     * <p>Null/empty when not a marketplace listing OR when the seller
     * didn't attach any handles. Service-layer validates via JSON
     * parse + a length cap so we don't accept arbitrary blobs.</p>
     */
    @Column(name = "payment_methods_json", columnDefinition = "TEXT")
    private String paymentMethodsJson;

    // -----------------------------------------------------------------
    // Community redesign — official / civic-report / news fields.
    // Contract: docs/design_handoff_community/backend/CONTRACT.md.
    // All nullable + additive; meaningful only on the matching feed-item
    // type. The FE derives feedItemType from (kind + authorType +
    // sponsored + civicStatus); these columns carry the per-type data.
    // -----------------------------------------------------------------

    /** official posts only — emergency | advisory | notice (OfficialTier wire). */
    @Column(name = "official_tier", length = 16)
    private String officialTier;

    /** Author-controlled pin (official posts). Mirrors GroupPost pin columns. */
    @Column(name = "pinned_at")
    private Instant pinnedAt;

    @Column(name = "pinned_by", length = 128)
    private String pinnedBy;

    /**
     * Emergency pin auto-expiry — how long this post stays at the TOP of the
     * feed. Null = no expiry.
     *
     * <p><b>CORRECTED 2026-08-22: this is NOT "the design's expiresAt", which
     * is what this comment used to claim.</b> Pinning is a ranking decision the
     * product makes; {@link #effectiveUntil} is when the advisory stops being
     * true, which the issuer states. They diverge in the ordinary case — a
     * 24-hour pin on a three-day flood warning — and rendering the pin window
     * as "In effect until…" would put a ranking decision in front of a reader
     * as a public-safety fact.</p>
     */
    @Column(name = "pinned_until")
    private Instant pinnedUntil;

    /**
     * {@code official} only — when the advisory stops being in effect.
     * Null = "until further notice", the honest default for an advisory with
     * no stated end.
     *
     * <p><b>Derived for dispatched alerts, captured for composed ones.</b> The
     * value was already on the wire and already stored before this field
     * existed: {@code AlertPost.expiresAt} is populated from the NWS
     * {@code ends}/{@code expires} in {@code AlertDispatchService}. It was
     * simply never copied onto the post, so the feed never saw it.
     * {@code AgencyAlertService} takes an explicit value instead — a city
     * writing "boil order until Thursday" has no upstream feed to derive
     * from.</p>
     *
     * <p>Distinct from {@link #pinnedUntil} (feed placement) and from
     * {@code sponsoredUntil} (paid placement). Three windows, three meanings.</p>
     */
    @Column(name = "effective_until")
    private Instant effectiveUntil;

    /**
     * {@code marketplace} only — free-form item condition ("Like new").
     *
     * <p>Column is {@code item_condition}: CONDITION is a reserved word in SQL
     * and a bare {@code condition} column forces quoting in every hand-written
     * query forever. The Java name stays the domain word.</p>
     *
     * <p>Not an enum. When the composer's chip set is decided the vocabulary
     * belongs beside {@code ResourceCategory} in Java, where BOTH writers can
     * read it — a Postgres CHECK is invisible to the second writer, which is
     * how the resource board ended up with two vocabularies.</p>
     */
    @Column(name = "item_condition", length = 40)
    private String condition;

    /** {@code marketplace} only — where/how to collect ("Porch, 400 N & Main"). */
    @Column(name = "pickup_note", length = 160)
    private String pickupNote;

    /** civic_report only — reported|acknowledged|scheduled|resolved (CivicStatus wire). */
    @Column(name = "civic_status", length = 16)
    private String civicStatus;

    /** civic_report only — pothole|streetlight|debris|water|other (CivicCategory wire). */
    @Column(name = "civic_category", length = 16)
    private String civicCategory;

    /**
     * The first/primary tagged agency (group publicId) — a DISPLAY MIRROR of the
     * civic_report_agency join (Slice 2, V53). Multi-agency tags live in the join
     * table; this single column is kept (decision 7) for back-compat readers and
     * is dual-written by CivicAgencyService. A later cleanup migration retires it.
     */
    @Column(name = "tagged_agency_group_id", length = 64)
    private String taggedAgencyGroupId;

    /**
     * The agency currently holding the active CLAIM on this civic report (group
     * publicId), or null when unclaimed — the denormalized single-claim mirror
     * (Slice 2, V53) of the claimed civic_report_agency row. Gates the
     * claim-only operations (schedule/resolve, work-order spawn, merge).
     */
    @Column(name = "claiming_agency_group_id", length = 64)
    private String claimingAgencyGroupId;

    /**
     * INPUT-ONLY (not persisted) — the civic-report composer's confirmed/adjusted
     * agency tag set at create (Slice 2 D2 auto-derive: the resolver pre-selects
     * covering agencies, the filer confirms/adjusts). Null on legacy clients that
     * still send the single {@link #taggedAgencyGroupId}. Reconciled + persisted
     * to the join by CivicAgencyService post-save.
     */
    @Transient
    private List<String> civicAgencyIds;

    /** Latest agency note on the civic card ("Acknowledged · work order #2287"). */
    @Column(name = "agency_note", length = 280)
    private String agencyNote;

    @Column(name = "civic_acked_at")
    private Instant civicAckedAt;

    @Column(name = "scheduled_for")
    private Instant scheduledFor;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** news only — outlet name / outbound URL / estimated read time (minutes). */
    @Column(name = "source_name", length = 120)
    private String sourceName;

    @Column(name = "source_url", length = 512)
    private String sourceUrl;

    @Column(name = "read_minutes")
    private Integer readMinutes;

    /**
     * For an agency work order (kind="task") created from a civic report,
     * the source civic-report post id (Phase 5 Slice H). Null otherwise —
     * this is what links the operational work order back to the public
     * card that prompted it.
     */
    @Column(name = "source_post_id")
    private Long sourcePostId;

    /**
     * Civic epic Slice 3 (V54) — merge link. When non-null, THIS civic report is
     * a DUPLICATE that has been merged INTO the canonical report with this id
     * (whose own {@code mergedIntoPostId} is always null — chains are flattened
     * in {@code CivicAgencyService}, never nested). Null = a live/canonical
     * report. The FK is {@code ON DELETE SET NULL} (a deleted survivor detaches
     * its duplicates to standalone, never cascade-destroys citizen data), and a
     * {@code CHECK} forbids self-merge — both Postgres-only (V54), so the H2 test
     * profile relies on the service guards. Merged status is READ-THROUGH: the
     * duplicate keeps its own {@link #civicStatus} frozen for history while read
     * surfaces resolve to the survivor's status for display (decision 1).
     */
    @Column(name = "merged_into_post_id")
    private Long mergedIntoPostId;

    @Column(name = "merged_at")
    private Instant mergedAt;

    @Column(name = "merged_by_email", length = 320)
    private String mergedByEmail;

    // -----------------------------------------------------------------
    // Unified Work Order fields — Phase 1 (V43__unified_workorder_schema).
    // Ported from the deleted legacy disaster-relief intake flow
    // (src/shared/tasks/Request/* @ commit 258adaf26). All additive +
    // nullable/defaulted; meaningful only on group/agency work orders,
    // inert on personal preparedness tasks and every non-task kind.
    // -----------------------------------------------------------------

    // --- Legacy hazard / triage (from WorkInfoForm.js) ---
    @Column(name = "near_power_lines", nullable = false,
            columnDefinition = "boolean NOT NULL DEFAULT false")
    private boolean nearPowerLines = false;

    @Column(name = "electrical_hazard", nullable = false,
            columnDefinition = "boolean NOT NULL DEFAULT false")
    private boolean electricalHazard = false;

    /** Free-form water-level note at the site (e.g. "ankle", "knee-deep"). */
    @Column(name = "water_level", length = 32)
    private String waterLevel;

    /** Tri-state: null = unknown, TRUE = safe to enter, FALSE = not safe. */
    @Column(name = "safe_to_enter")
    private Boolean safeToEnter;

    // --- Liability / release (from ReleaseForm.js) ---
    /**
     * Whether this work order requires a signed liability waiver before it may
     * be actioned. Default false — personal tasks and every legacy row are
     * ungated. When true, {@code ck_task_liability_gate} forbids the row from
     * resting in IN_PROGRESS / VERIFICATION_PENDING / CLOSED / DONE unless
     * {@link #releaseSigned} is true OR {@link #releaseExceptionReason} is set.
     */
    @Column(name = "liability_required", nullable = false,
            columnDefinition = "boolean NOT NULL DEFAULT false")
    private boolean liabilityRequired = false;

    @Column(name = "release_signed", nullable = false,
            columnDefinition = "boolean NOT NULL DEFAULT false")
    private boolean releaseSigned = false;

    /** SHA-256 (hex) of the exact waiver copy the requester agreed to. */
    @Column(name = "release_text_hash", length = 64)
    private String releaseTextHash;

    /**
     * The legacy "requester did not sign" escape hatch — a required reason
     * (not present / refused / language barrier). A non-null value satisfies
     * the liability gate without a signature.
     */
    @Column(name = "release_exception_reason", length = 500)
    private String releaseExceptionReason;

    // --- Dynamic, need-type-specific intake bag (V47__add_work_details_jsonb) ---
    /**
     * Sparse, need-type-specific work-order intake captured by the civic /
     * relief {@code WorkOrderWizard} Site &amp; Triage step — e.g. number of
     * trees (tree removal), occupancy adults/children + dietary notes
     * (food/supplies), a free description (other), plus a general hazard note.
     *
     * <p>The stable, flat triage columns ({@link #nearPowerLines},
     * {@link #electricalHazard}, {@link #waterLevel}, {@link #safeToEnter})
     * stay first-class; this bag holds only the remainder whose shape varies
     * by {@code needType} and evolves per wizard phase — jsonb beats a dozen
     * nullable columns and needs zero further migrations to grow.</p>
     *
     * <p>Native Hibernate 6 JSONB mapping (same as
     * {@code MealPlan.ingredients}): {@code @JdbcTypeCode(SqlTypes.JSON)} +
     * {@code columnDefinition = "jsonb"}. Null on personal tasks and every
     * non-work-order kind (the common case) — left null-default rather than an
     * empty map so those rows store SQL NULL, not {@code '{}'}.</p>
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "work_details", columnDefinition = "jsonb")
    private Map<String, Object> workDetails;

    /**
     * Denormalized need-type discriminator (V47) — also carried inside
     * {@link #workDetails} (as {@code needType}) for self-description. Promoted
     * to a first-class, indexed column so dispatch/triage queries filter on an
     * index rather than a jsonb path lookup (EXECUTION_GAME_PLAN_WIZARD.md
     * §3.1). String codes from the wizard need-type vocabulary (tree_debris |
     * flood_water | roof_structural | hazmat_utility | civic_hazard |
     * rescue_welfare | animal_rescue | other). Null on personal tasks and every
     * non-work-order kind. Kept a plain {@code String} (not a Postgres enum) so
     * the taxonomy can grow without a type migration.
     */
    @Column(name = "need_type", length = 32)
    private String needType;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (status == null) status = PostStatus.OPEN;
        if (priority == null) priority = PostPriority.MEDIUM;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public enum PostStatus {
        // Original 5-state lifecycle (unchanged; DONE retained as a terminal
        // alias of CLOSED for back-compat with existing rows).
        OPEN, CLAIMED, IN_PROGRESS, DONE, CANCELLED,
        // Unified work-order state machine additions (Phase 1). DRAFT and
        // LIABILITY_PENDING bracket the front of the flow; VERIFICATION_PENDING
        // and CLOSED bracket the back. Liability-gated tasks cannot reach
        // IN_PROGRESS / VERIFICATION_PENDING / CLOSED / DONE unsigned — enforced
        // by ck_task_liability_gate (V43), not just here.
        DRAFT, LIABILITY_PENDING, VERIFICATION_PENDING, CLOSED,
        // Terminal archival state. A nightly sweep (WorkOrderArchivalService)
        // flips work orders (kind="task") that have been DONE longer than the
        // retention window (7d) to ARCHIVED so the primary operational list
        // stays clean; the FE surfaces them behind an "Archive" filter. Stored
        // in the same @Enumerated(STRING) VARCHAR(32) status column — no schema
        // change, and it is not in ck_task_liability_gate's forbidden set.
        ARCHIVED
    }

    public enum PostPriority {
        LOW, MEDIUM, HIGH, URGENT
    }
}
