package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One row per household activity event — the chronological backbone of the
 * household chat's "system event" rows ("Bobby marked Safe", "Check-in
 * started", "Mom is together with family"). Replaces the frontend's
 * client-side synthesis from {@code members[].selfStatus.updatedAt} +
 * {@code accompaniments[].since} + alert state, which loses fidelity (no
 * actor, no end events, no cross-device ordering).
 *
 * <p>Householdid is a group id under the hood — household IS a group with
 * {@code groupType = "Household"}. The column name is {@code household_id}
 * so a future split (if households ever leave the group table) doesn't
 * force a column rename.</p>
 */
@Entity
@Table(
        name = "household_event",
        indexes = {
                @Index(name = "idx_household_event_hh_at", columnList = "household_id,at"),
                @Index(name = "idx_household_event_kind", columnList = "kind")
        }
)
@Getter
@Setter
public class HouseholdEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "household_id", nullable = false, length = 64)
    private String householdId;

    /**
     * Event kind. Open enum (kept as String so adding a new kind is just a
     * service-layer change). Current vocabulary:
     * <ul>
     *   <li>{@code status-changed} — payload: {@code { status: "SAFE"|... }}</li>
     *   <li>{@code checkin-started} — payload: {}</li>
     *   <li>{@code checkin-ended} — payload: {}</li>
     *   <li>{@code with-claim} — payload: {@code { subjectEmail }}</li>
     *   <li>{@code with-release} — payload: {@code { subjectEmail }}</li>
     *   <li>{@code member-added} / {@code member-removed} — payload:
     *       {@code { subjectEmail }}</li>
     * </ul>
     */
    @Column(nullable = false, length = 32)
    private String kind;

    @Column(nullable = false)
    private Instant at;

    /** Email of who triggered the event. Null for system-generated rows. */
    @Column(name = "actor_email", length = 255)
    private String actorEmail;

    /**
     * Kind-specific JSON payload. Stored as TEXT so we don't need a JSONB
     * dependency; the service layer (de)serializes via Jackson. Small by
     * design — large payloads should reference other entities by id.
     */
    @Column(columnDefinition = "TEXT")
    private String payloadJson;

    @PrePersist
    void onCreate() {
        if (at == null) at = Instant.now();
    }
}
