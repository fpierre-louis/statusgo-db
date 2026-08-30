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
     * service-layer change). The constants live on
     * {@link io.sitprep.sitprepapi.service.HouseholdEventService} — read them
     * from there rather than retyping a literal, which is how this list went
     * three kinds stale before 2026-08-30.
     *
     * <p>Current vocabulary:</p>
     * <ul>
     *   <li>{@code status-changed} — payload: {@code { status: "SAFE"|... }}.
     *       A status write made while the household has NO active check-in.</li>
     *   <li>{@code checkin-replied} — payload: {@code { status }}. The SAME
     *       write, made while a check-in is open. One row either way: a reply
     *       and a status change are one act, and recording both would put two
     *       names on one fact. The kind carries which it was, recorded at write
     *       time from state the service holds — never inferred later from a
     *       timestamp falling inside a window.</li>
     *   <li>{@code checkin-started} / {@code checkin-ended} — payload: {}</li>
     *   <li>{@code checkin-reminder} — payload: {@code { slotIndex }}.
     *       System-fired, {@code actorEmail} null.</li>
     *   <li>{@code nudge} — payload: {@code { subjectEmail }}. One row per
     *       person pushed, so the Timeline can say who was nudged rather than
     *       that a nudge happened.</li>
     *   <li>{@code with-claim} / {@code with-release} — payload:
     *       {@code { subjectEmail }}</li>
     *   <li>{@code member-added} / {@code member-removed} — payload:
     *       {@code { subjectEmail }}</li>
     *   <li>{@code member-confirmed-meeting} / {@code -contacts} / {@code -evac}
     *       — payload: {}</li>
     *   <li>{@code weekly-check-in-completed} — payload: {@code { mood }}</li>
     *   <li>{@code ritual-fired} — payload: {@code { ritualKind, recipients }}.
     *       System-fired, {@code actorEmail} null.</li>
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
