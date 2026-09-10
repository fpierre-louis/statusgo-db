package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * The household's PREPARED arrangement for helping one person — who they have
 * decided should help, before anything happens.
 *
 * <p><b>This is plan state and nothing else.</b> There is deliberately no
 * {@code acceptedAt}, no {@code acknowledgedAt} and no availability column.
 * Adding one would let a surface imply coverage nobody confirmed, which is the
 * RC-2 invariant this table is most at risk of breaking:</p>
 *
 * <pre>
 *   "Primary support: Marcus"        — plan state. True the moment it is saved.
 *   "Marcus is handling Grandma"     — a claim about the present. NOT TRUE
 *                                      until Marcus says so, and there is
 *                                      currently no place for him to say it.
 * </pre>
 *
 * <p>Runtime acceptance is Level 3 (Emergency Support Coordination), scoped to a
 * {@code PlanActivation} and following the {@link PlanActivationAck} shape. It
 * is not shipped, and shipping assignments does not make it exist.</p>
 *
 * <p><b>A helper is a member or a contact — never a fake user.</b> The
 * neighbour, the paid aide, the caseworker and the inland daughter are all
 * already representable as {@link EmergencyContact}, which carries a name,
 * phone, role and subject link and is already cached and printed. Creating a
 * pseudo-user for them would produce an unauthenticated row that looks like a
 * member on every roster and map.</p>
 */
@Entity
@Table(
        name = "emergency_support_assignment",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_support_assignment_subject_role",
                columnNames = {"household_id", "subject_type", "subject_id", "role"}
        )
)
public class EmergencySupportAssignment {

    /** Which slot this helper fills. Two, deliberately: a list invites a phone tree. */
    public enum Role { PRIMARY, BACKUP }

    /** Whether the helper is a SitPrep member or an emergency contact. */
    public enum HelperType { MEMBER, CONTACT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "household_id", nullable = false, length = 64)
    private String householdId;

    /** Who needs the help: {@code user} | {@code manual}. */
    @Column(name = "subject_type", nullable = false, length = 16)
    private String subjectType;

    @Column(name = "subject_id", nullable = false, length = 255)
    private String subjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "helper_type", nullable = false, length = 16)
    private HelperType helperType;

    /** Set when {@code helperType = MEMBER}. */
    @Column(name = "helper_user_email", length = 255)
    private String helperUserEmail;

    /** Set when {@code helperType = CONTACT} — an {@link EmergencyContact} id. */
    @Column(name = "helper_contact_id")
    private Long helperContactId;

    /**
     * Resolved at write time so a printed plan and an offline cache still name
     * the helper when the contact row is unreachable. A stale name is a lesser
     * failure than a blank line on a plan somebody is holding in a blackout.
     */
    @Column(name = "helper_name", length = 160)
    private String helperName;

    /** How they help — "has the spare key and the ramp". Not a job description. */
    @Column(name = "helper_note", length = 160)
    private String helperNote;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "assigned_by_email", length = 255)
    private String assignedByEmail;

    @PrePersist
    @PreUpdate
    void stampAssignedAt() {
        if (assignedAt == null) assignedAt = Instant.now();
    }

    public Long getId() { return id; }

    public String getHouseholdId() { return householdId; }
    public void setHouseholdId(String v) { this.householdId = v; }

    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String v) { this.subjectType = v; }

    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String v) { this.subjectId = v; }

    public Role getRole() { return role; }
    public void setRole(Role v) { this.role = v; }

    public HelperType getHelperType() { return helperType; }
    public void setHelperType(HelperType v) { this.helperType = v; }

    public String getHelperUserEmail() { return helperUserEmail; }
    public void setHelperUserEmail(String v) { this.helperUserEmail = v; }

    public Long getHelperContactId() { return helperContactId; }
    public void setHelperContactId(Long v) { this.helperContactId = v; }

    public String getHelperName() { return helperName; }
    public void setHelperName(String v) { this.helperName = v; }

    public String getHelperNote() { return helperNote; }
    public void setHelperNote(String v) { this.helperNote = v; }

    public Instant getAssignedAt() { return assignedAt; }
    public void setAssignedAt(Instant v) { this.assignedAt = v; }

    public String getAssignedByEmail() { return assignedByEmail; }
    public void setAssignedByEmail(String v) { this.assignedByEmail = v; }
}
