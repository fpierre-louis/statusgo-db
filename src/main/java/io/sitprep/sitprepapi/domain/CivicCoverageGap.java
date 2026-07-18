package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Civic epic Slice 2 (V53) — the zip-keyed orphan ledger (decision 2). When a
 * civic report's location has NO covering authorized agency, the report still
 * persists normally (visible, status=reported), and its zip is recorded here as
 * a coverage-gap demand signal the ghost/onboarding pipeline reads to prioritize
 * which jurisdictions to recruit.
 *
 * <p>Deliberately NOT hung off {@code GhostDemandVote} (which requires an
 * existing GHOST group) — the orphan case is precisely "no group exists yet", so
 * a group-less zip ledger keeps the ghost ledger's "one group, distinct
 * residents" invariant intact. One row per zip; {@code reportCount} accumulates.</p>
 */
@Entity
@Getter
@Setter
@Table(
        name = "civic_coverage_gap",
        uniqueConstraints = @UniqueConstraint(name = "uk_civic_coverage_gap_zip", columnNames = "zip"))
public class CivicCoverageGap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "zip", nullable = false, length = 12)
    private String zip;

    /** Most-recent civic category to hit this gap — a prioritization hint. */
    @Column(name = "last_category", length = 24)
    private String lastCategory;

    @Column(name = "report_count", nullable = false)
    private int reportCount;

    @Column(name = "first_seen", nullable = false)
    private Instant firstSeen;

    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (firstSeen == null) firstSeen = now;
        if (lastSeen == null) lastSeen = now;
    }
}
