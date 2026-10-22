package io.sitprep.sitprepapi.practice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One answer to one check-in (V122). A household-level fact with NO actor: who tapped
 * is deliberately not recorded. {@code correct} is internal and never serialized.
 */
@Entity
@Getter
@Setter
@Table(name = "practice_checkin_answer")
public class PracticeCheckInAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "household_id", nullable = false, length = 255)
    private String householdId;

    @Column(name = "skill_key", nullable = false, length = 96)
    private String skillKey;

    @Column(name = "checkin_key", nullable = false, length = 96)
    private String checkinKey;

    @Column(name = "content_version", nullable = false)
    private int contentVersion;

    @Column(name = "correct", nullable = false)
    private boolean correct;

    /** {@code HAD_IT}, {@code PARTLY}, {@code NOT_YET}, or null. */
    @Column(name = "confidence", length = 16)
    private String confidence;

    @Column(name = "answered_at", nullable = false)
    private Instant answeredAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @PrePersist
    void onCreate() {
        if (answeredAt == null) answeredAt = Instant.now();
    }
}
