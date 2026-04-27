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

    @Column(name = "message_preview", length = 2048)
    private String messagePreview;

    private Double lat;
    private Double lng;

    @Column(name = "activated_at", nullable = false)
    private Instant activatedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

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
