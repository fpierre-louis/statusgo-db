package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One row per (ghost group, resident) — the distinct-resident ledger behind
 * {@link Group#getGhostDemandSignal()} (Phase 3, V46). The unique
 * {@code (group_id, voter_email)} makes a resident's demand vote idempotent: a
 * single person can only ever add +1 to a group's demand signal, so nobody can
 * inflate the counter and trigger unwanted claim outreach.
 */
@Entity
@Getter
@Setter
@Table(
        name = "ghost_demand_vote",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ghost_demand_vote",
                columnNames = {"group_id", "voter_email"}
        )
)
public class GhostDemandVote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private String groupId;

    /** The voting resident's account email — used ONLY for the once-per-resident constraint. */
    @Column(name = "voter_email", nullable = false, length = 320)
    private String voterEmail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
