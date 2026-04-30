package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A task / request-for-help. Three scopes all share this one entity:
 *
 * <ul>
 *   <li><b>Group task</b> — {@code groupId != null && claimedByGroupId == null}.
 *       Visible only to that group's members. The traditional work-order flow.</li>
 *   <li><b>Community / personal task</b> — {@code groupId == null}. Visible to
 *       anyone in the {@code latitude/longitude} + radius set by the viewer.
 *       Used when an individual asks for help and any nearby group can claim.</li>
 *   <li><b>Group-claimed community task</b> — community-scope task that a group
 *       leader has claimed on behalf of their group. Both the requester and
 *       the claimer-group's members see live status.</li>
 * </ul>
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
                @Index(name = "idx_task_claimer", columnList = "claimed_by_email")
        }
)
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null = personal/community-scope; non-null = bound to that group. */
    @Column(name = "group_id")
    private String groupId;

    @Column(name = "requester_email", nullable = false)
    private String requesterEmail;

    /** The group that claimed this task (community → claimed). Null while open. */
    @Column(name = "claimed_by_group_id")
    private String claimedByGroupId;

    /** The specific user inside the claimer group who took it on. Null while open. */
    @Column(name = "claimed_by_email")
    private String claimedByEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskPriority priority;

    /**
     * Post kind — the row's role in the community feed. Per
     * {@code docs/MARKETPLACE_AND_FEED_CALM.md} "Feed: post types
     * beyond Asks", the same {@code Task} entity now carries the full
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
    @Column(name = "kind", nullable = false, length = 32)
    private String kind = "ask";

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 4096)
    private String description;

    /** For radius filtering on community-scope tasks. Null otherwise. */
    private Double latitude;
    private Double longitude;

    /** First 3 chars of postcode — see class doc. */
    @Column(name = "zip_bucket", length = 8)
    private String zipBucket;

    @Column(name = "due_at")
    private Instant dueAt;

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
    private List<String> imageKeys = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "task_tags", joinColumns = @JoinColumn(name = "task_id"))
    @Column(name = "tag")
    private Set<String> tags = new HashSet<>();

    /** For sub-task hierarchies (work-order breakdowns). Null for top-level tasks. */
    @Column(name = "parent_task_id")
    private Long parentTaskId;

    // -----------------------------------------------------------------
    // Sponsored content fields — docs/SPONSORED_AND_ALERT_MODE.md
    // build-order step 3. v1 sponsorship is admin-flagged (no self-
    // serve creation flow yet); these columns let TaskService.discover-
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

    @Column(name = "sponsored", nullable = false)
    private boolean sponsored = false;

    @Column(name = "crisis_relevant", nullable = false)
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
    @Column(name = "is_free", nullable = false)
    private boolean isFree = false;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (status == null) status = TaskStatus.OPEN;
        if (priority == null) priority = TaskPriority.MEDIUM;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public enum TaskStatus {
        OPEN, CLAIMED, IN_PROGRESS, DONE, CANCELLED
    }

    public enum TaskPriority {
        LOW, MEDIUM, HIGH, URGENT
    }
}
