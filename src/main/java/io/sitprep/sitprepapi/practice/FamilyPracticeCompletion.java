package io.sitprep.sitprepapi.practice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One household-level completion of one immutable Family Practice version. */
@Entity
@Getter
@Setter
@Table(name = "family_practice_completion", uniqueConstraints = @UniqueConstraint(
        name = "uk_family_practice_completion_version",
        columnNames = {"household_id", "activity_key", "content_version"}))
public class FamilyPracticeCompletion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "household_id", nullable = false, length = 255)
    private String householdId;

    @Column(name = "activity_key", nullable = false, length = 96)
    private String activityKey;

    @Column(name = "content_version", nullable = false)
    private int contentVersion;

    @Column(name = "content_hash", nullable = false, length = 128)
    private String contentHash;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    @Column(name = "completed_by_email", nullable = false, length = 320)
    private String completedByEmail;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "debrief_tags", nullable = false)
    private List<String> debriefTags = new ArrayList<>();

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @PrePersist
    void onCreate() {
        if (completedAt == null) completedAt = Instant.now();
        if (debriefTags == null) debriefTags = new ArrayList<>();
    }
}
