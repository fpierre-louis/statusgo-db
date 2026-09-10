package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Evidence that a specific person was actually asked to check in, within a
 * specific check-in window.
 *
 * <p><b>Why this exists (RC-2).</b> Before this table, {@code
 * GroupService.requestCheckIn} validated authority and fired notifications and
 * persisted nothing. The roster's {@code NO RESPONSE} derivation keys on the
 * alert <i>window</i>, not on any record that a person was asked — so a member
 * whose notification category is muted hits {@code Lane.DROP}, is never
 * notified in any form, leaves no log row, and renders identically to a member
 * who was asked and stayed silent. The household reads "no response" and infers
 * "they did not answer us". Both readings are wrong.</p>
 *
 * <p><b>What a row means, exactly.</b> "At {@code requestedAt}, {@code
 * requestedByEmail} asked {@code subjectEmail} for a status, for the window
 * that opened at {@code windowStartedAt}." It is a record of the ASK. It says
 * nothing about whether a message was dispatched, delivered, or seen — that is
 * a separate dimension, answered by {@code NotificationLog}, and deliberately
 * not folded in here.</p>
 *
 * <p><b>Why the window is part of the identity.</b> A check-in is only
 * meaningful inside the situation that prompted it. Asking during last week's
 * drill is not asking during tonight's tornado warning, and a single row per
 * (group, person) would let the first masquerade as the second. The unique
 * constraint is on all three columns so a re-ask inside the same window is an
 * upsert rather than a duplicate, and a new window always needs a new ask.</p>
 *
 * <p>Scoped to a group rather than to a {@code PlanActivation} because a
 * household can run a check-in without activating a plan — the two states are
 * independent down to the schema, and {@code useAllClear} ends both precisely
 * because they are.</p>
 */
@Entity
@Table(
        name = "check_in_request",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_check_in_request_group_subject_window",
                columnNames = {"group_id", "subject_email", "window_started_at"}
        )
)
public class CheckInRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code Group.groupId} — the public id, matching every other group reference. */
    @Column(name = "group_id", nullable = false, length = 64)
    private String groupId;

    /** Who was asked. Lower-cased on write, like every other email column. */
    @Column(name = "subject_email", nullable = false, length = 255)
    private String subjectEmail;

    /**
     * When the check-in window this ask belongs to opened —
     * {@code Group.alertActivatedAt}, or the request time when a nudge is sent
     * outside an active alert.
     */
    @Column(name = "window_started_at", nullable = false)
    private Instant windowStartedAt;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    /** Who asked. Null only if the actor could not be resolved. */
    @Column(name = "requested_by_email", length = 255)
    private String requestedByEmail;

    protected CheckInRequest() {}

    public CheckInRequest(String groupId, String subjectEmail, Instant windowStartedAt,
                          Instant requestedAt, String requestedByEmail) {
        this.groupId = groupId;
        this.subjectEmail = subjectEmail;
        this.windowStartedAt = windowStartedAt;
        this.requestedAt = requestedAt;
        this.requestedByEmail = requestedByEmail;
    }

    public Long getId() { return id; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getSubjectEmail() { return subjectEmail; }
    public void setSubjectEmail(String subjectEmail) { this.subjectEmail = subjectEmail; }

    public Instant getWindowStartedAt() { return windowStartedAt; }
    public void setWindowStartedAt(Instant windowStartedAt) { this.windowStartedAt = windowStartedAt; }

    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }

    public String getRequestedByEmail() { return requestedByEmail; }
    public void setRequestedByEmail(String requestedByEmail) { this.requestedByEmail = requestedByEmail; }
}
