package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One checked-off line of a household's 14-Day At-Home Stockpile: the mere
 * existence of a row means "we have this on hand". There is no boolean — the
 * toggle endpoint inserts or deletes the row.
 *
 * <p>Only the NON-FOOD stockpile items are tracked here (Sanitation / Power &amp;
 * Heat / Medical / Tools &amp; Safety). Food &amp; Water stays DERIVED from the Food
 * Planner and is never written to this table. {@code item_key} is always a
 * {@code stockpile-*} key; the service validates it against the catalog before
 * persisting, so a go-bag key can never land here (decoupling, T-15).</p>
 *
 * <p><b>Uses a DB-generated {@code BIGSERIAL} id</b> (not a client-assigned
 * {@code @Id} like {@code GoBagItem}). That matters: a new row has a null id, so
 * {@code save()} runs {@code persist()} — {@code @PrePersist} fires on THIS managed
 * instance and {@code createdAt} is set. The client-{@code @Id} "merge fires
 * {@code @PrePersist} on a copy → null {@code createdAt}" trap (SYSTEM_TRAPS T-1)
 * cannot occur here.</p>
 */
@Entity
@Table(
        name = "home_stockpile_item",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_home_stockpile_item",
                columnNames = {"household_id", "item_key"})
)
@Getter
@Setter
public class HomeStockpileItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "household_id", nullable = false, length = 64)
    private String householdId;

    /** A {@code stockpile-*} non-food item key (validated against the catalog). */
    @Column(name = "item_key", nullable = false, length = 80)
    private String itemKey;

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
