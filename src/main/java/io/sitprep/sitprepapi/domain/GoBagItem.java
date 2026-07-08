package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One checklist line inside a {@link GoBag}: a scaled quantity to pack,
 * the packed progress, and an optional expiry date that drives the
 * rotation-reminder sweep ({@code GoBagExpiryReminderService}).
 *
 * <p>{@code qtyRecommended} is deliberately denormalized — it snapshots
 * what the household looked like when the recommendation engine seeded
 * the item. Re-running the wizard refreshes it (additive merge on
 * {@code itemKey}; packed progress is user labor and is never clobbered).</p>
 *
 * <p>Fire-exactly-once reminders: the sweep filters on
 * {@code reminderSentAt IS NULL} and stamps it after firing — the same
 * single-column latch {@code PersonalTaskReminderService} uses. Editing
 * {@code expiresOn} (or tapping "Mark refreshed") clears the latch so a
 * replaced item re-arms naturally.</p>
 */
@Entity
@Table(
        name = "go_bag_item",
        indexes = @Index(name = "idx_go_bag_item_bag", columnList = "bag_id")
)
@Getter
@Setter
public class GoBagItem {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "bag_id", nullable = false, length = 64)
    private String bagId;

    /** Recommendation-template key ("water"), or "custom:<uuid>". */
    @Column(name = "item_key", nullable = false, length = 80)
    private String itemKey;

    /**
     * Frontend productRegistry key ("gobag-water-pouches"), or null when the
     * item has no product mapping. Persisted (denormalized from the template
     * at seed time) so the item editor's "Buy this" affiliate links resolve
     * without the FE needing an itemKey→productKey map.
     */
    @Column(name = "product_key", length = 80)
    private String productKey;

    /** Denormalized display name (template copy at seed time, or custom). */
    @Column(nullable = false, length = 160)
    private String label;

    /** water | firstaid | power | shelter | documents | tools */
    @Column(nullable = false, length = 32)
    private String category;

    /** 0 = grab-list minimum, 1 = recommended, 2 = situational. */
    @Column(nullable = false)
    private int priority;

    @Column(name = "qty_recommended", nullable = false)
    private int qtyRecommended;

    @Column(name = "qty_packed", nullable = false)
    private int qtyPacked;

    /** "gal", "count", "sets", "supplies" — display-only. */
    @Column(length = 24)
    private String unit;

    /** Null = never expires / no reminder. */
    @Column(name = "expires_on")
    private LocalDate expiresOn;

    /** Fire-once latch for the expiry sweep. */
    @Column(name = "reminder_sent_at")
    private Instant reminderSentAt;

    @Column(length = 500)
    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
