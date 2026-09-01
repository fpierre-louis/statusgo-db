package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * A single "activate plan" event: the owner tells recipients where to meet
 * and/or evacuate to. The {@link #id} is opaque and shared as the shareable
 * link ({@code /deployedplan?activationId=...}) so the owner's email is not
 * leaked in the URL. Meeting place / evac plan are referenced by id and
 * resolved on read so the recipient sees the current state of the owner's
 * plan — if the owner later edits their plan, the recipient sees the edit.
 */
@Entity
@Getter
@Setter
@Table(name = "plan_activations")
public class PlanActivation {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "activation_id", unique = true, updatable = false)
    private String id;

    /**
     * Optimistic-locking token — audit P1-6. JPA increments on every flush;
     * concurrent updates that race on a stale read fail with
     * {@code OptimisticLockingFailureException}, which the global handler
     * surfaces as HTTP 409 {@code STALE_WRITE}.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "owner_email", nullable = false)
    private String ownerEmail;

    /** Snapshot of the owner's UserInfo.id at activation time. */
    @Column(name = "owner_user_id")
    private String ownerUserId;

    /** Snapshot of "First Last" at activation time. */
    @Column(name = "owner_name")
    private String ownerName;

    @Column(name = "meeting_place_id")
    private Long meetingPlaceId;

    @Column(name = "evac_plan_id")
    private Long evacPlanId;

    /** 'stay-home' | 'custom' | 'meet-shelter-direct' */
    @Column(name = "meeting_mode")
    private String meetingMode;

    /** 'shelter' | 'evac-home' | 'evac-stay-put' */
    @Column(name = "evac_mode")
    private String evacMode;

    /** NORMAL | PREPARING | GATHERING | SHELTERING | EVACUATING | RECOVERY. */
    @Column(name = "operational_mode")
    private String operationalMode;

    /** Resolved safety-contract movement directive for the governing alert. */
    @Column(name = "movement_directive")
    private String movementDirective;

    @Column(name = "governing_alert_source")
    private String governingAlertSource;

    @Column(name = "governing_alert_id", length = 1024)
    private String governingAlertId;

    @Column(name = "governing_alert_event")
    private String governingAlertEvent;

    @Column(name = "governing_alert_headline", length = 1024)
    private String governingAlertHeadline;

    @Column(name = "governing_alert_lifecycle_state")
    private String governingAlertLifecycleState;

    @Column(name = "message_preview", length = 2048)
    private String messagePreview;

    private Double lat;
    private Double lng;

    @Column(name = "activated_at", nullable = false)
    private Instant activatedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * When the household said it was over, or null while it is still running.
     *
     * <p>DISTINCT FROM {@link #expiresAt}, and the distinction is the point.
     * {@code expiresAt} is a 72-hour timer that runs whether or not anything
     * happened; {@code endedAt} is a person saying so. Before this column
     * existed the timer was the only way an activation stopped, which is why
     * Home read EVACUATING for three days off a row nobody could close.</p>
     *
     * <p>Both active queries in {@code PlanActivationRepo} require this to be
     * null, so setting it is what removes the activation from every "is
     * something happening" read — including {@code MeDto.activeActivationId},
     * which is what Home ranks on.</p>
     *
     * <p><b>It does not close the recipient link and it does not stop acks.</b>
     * The owner ends it because everyone they can see is safe; the straggler who
     * has not replied yet is exactly the person whose "I need help" must still
     * land.</p>
     */
    @Column(name = "ended_at")
    private Instant endedAt;

    /** Who ended it — the owner or a household co-member. Null while running. */
    @Column(name = "ended_by_email")
    private String endedByEmail;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_activation_household_members",
            joinColumns = @JoinColumn(name = "activation_id"))
    @Column(name = "household_member_id")
    private Set<String> householdMemberIds = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_activation_contact_ids",
            joinColumns = @JoinColumn(name = "activation_id"))
    @Column(name = "contact_id")
    private Set<Long> contactIds = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_activation_contact_group_ids",
            joinColumns = @JoinColumn(name = "activation_id"))
    @Column(name = "contact_group_id")
    private Set<Long> contactGroupIds = new HashSet<>();
}
