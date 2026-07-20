package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One STAFF relationship of a person to an agency-authorized {@link Group}. Staff
 * is the non-admin employee who works the agency's queue but must NOT be able to
 * change agency settings — the persona that {@code OWNER/ADMIN/MEMBER/NONE} could
 * not express (a resident who self-joins an agency group is also {@code MEMBER},
 * so {@code MEMBER} is ambiguous; see docs/lanes/AGENCY_STAFF_PHASE0_DESIGN.md).
 *
 * <p>Owner decision: a SEPARATE additive join table, deliberately NOT a fourth
 * role-enum value — both for safety (a role-enum change needs the Java enum +
 * Flyway CHECK + H2 config in lockstep, and H2 can't catch a Postgres constraint
 * gap — trap T-4) and design (staff must be INDEPENDENT of group role — the same
 * person can be both a staff member and a resident who joined). This table is the
 * SOLE authority for staff.</p>
 *
 * <p>Mirrors the {@link TaskAssignee} shape: plain {@code group_id} column (not a
 * JPA relationship), DB-generated {@code IDENTITY} id (sidesteps the client-id
 * {@code save()}-returns-managed / null-timestamp trap), email stored lower-cased
 * by the writer. The FK to {@code groups(group_id) ON DELETE CASCADE} lives in the
 * V55 migration (Postgres-only, not a JPA annotation); the H2 test profile builds
 * this table from the entity via {@code ddl-auto=create-drop} and does NOT exercise
 * the FK — it is validated by the V55 rehearsal against real Postgres instead.</p>
 */
@Entity
@Table(
        name = "agency_staff",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_agency_staff_user_group",
                columnNames = { "user_email", "group_id" }
        ),
        indexes = {
                @Index(name = "idx_agency_staff_user", columnList = "user_email"),
                @Index(name = "idx_agency_staff_group", columnList = "group_id")
        }
)
@Getter
@Setter
public class AgencyStaff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The agency-authorized group. FK -> groups(group_id) ON DELETE CASCADE (V55). */
    @Column(name = "group_id", nullable = false, length = 255)
    private String groupId;

    /** Staff member's email, stored lower-cased + trimmed by the writer. No FK to user_info. */
    @Column(name = "user_email", nullable = false, length = 255)
    private String userEmail;

    /** Verified caller who granted staff (audit/accountability parity with task_assignee). */
    @Column(name = "added_by", length = 255)
    private String addedBy;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    @PrePersist
    void onCreate() {
        if (addedAt == null) addedAt = Instant.now();
    }
}
