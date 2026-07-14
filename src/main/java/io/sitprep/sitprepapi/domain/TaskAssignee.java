package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One assignment of a person to a work-order {@link Post} (a {@code kind="task"}
 * row on the {@code task} table), with a task-level {@link Role} of {@code LEAD}
 * or {@code HELPER}. This collection is the <b>sole source of truth</b> for
 * work-order assignment membership and roles (Step 2 — see
 * {@code docs/DOCS_STEP2_ROLE_MODEL_DESIGN.md}).
 *
 * <p><b>Read-authority for "who is the Lead" is this table</b> — the row where
 * {@code role = LEAD} (0 or 1 per task, DB-enforced by a partial-unique index in
 * V48). Nothing reads {@code Post.assigneeEmail} to determine a role;
 * {@code assignee_email} is demoted to a non-authoritative "primary display"
 * mirror maintained write-through by the single assignment writer.</p>
 *
 * <p>Mirrors the {@link PostReaction} shape (plain {@code task_id} column, not a
 * JPA relationship; DB-generated {@code IDENTITY} id — deliberately NOT a
 * client-assigned id, so it sidesteps the {@code save()}-returns-managed /
 * null-{@code createdAt} trap). The FK to {@code task(id)}, the {@code role}
 * CHECK, and the partial-unique Lead index live in the V48 migration (Postgres),
 * not in JPA annotations — the H2 test profile builds this table from the entity
 * via {@code ddl-auto=create-drop} and does not exercise those Postgres-only
 * constraints (validated by the V48 rehearsal instead).</p>
 */
@Entity
@Table(
        name = "task_assignee",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_task_assignee_task_email",
                columnNames = { "task_id", "email" }
        ),
        indexes = {
                @Index(name = "idx_task_assignee_task", columnList = "task_id"),
                @Index(name = "idx_task_assignee_email", columnList = "email")
        }
)
@Getter
@Setter
public class TaskAssignee {

    /** Task-level role. Distinct from the group's Admin/Volunteer roles. */
    public enum Role { LEAD, HELPER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * FK → {@code task(id)} (the work-order {@link Post}). Named {@code postId}
     * to match the {@link PostReaction} convention (field {@code postId} →
     * column {@code task_id}); the physical table is {@code task}.
     */
    @Column(name = "task_id", nullable = false)
    private Long postId;

    /** Assignee's email, stored lower-cased by the assignment writer. */
    @Column(nullable = false, length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    /** Verified caller who created this assignment (audit parity). */
    @Column(name = "assigned_by", length = 255)
    private String assignedBy;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
