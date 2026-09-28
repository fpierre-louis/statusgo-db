package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One person's latest word on a hazard report: {@code still} ("still there")
 * or {@code gone} (V87). Unique per (report, person) — a new vote replaces
 * the old one, so counts are counts of distinct people by construction.
 */
@Entity
@Table(
        name = "hazard_vote",
        uniqueConstraints = @UniqueConstraint(name = "uk_hazard_vote_task_user", columnNames = { "task_id", "user_email" }),
        indexes = @Index(name = "idx_hazard_vote_task_time", columnList = "task_id,voted_at")
)
@Getter
@Setter
public class HazardVote {

    public static final String STILL = "still";
    public static final String GONE = "gone";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "user_email", nullable = false, length = 320)
    private String userEmail;

    @Column(name = "vote", nullable = false, length = 8)
    private String vote;

    @Column(name = "voted_at", nullable = false)
    private Instant votedAt;
}
