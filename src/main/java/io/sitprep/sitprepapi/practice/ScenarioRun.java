package io.sitprep.sitprepapi.practice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One adult's (or household's) walk through one Practice scenario (V104).
 * In-progress, completed and abandoned runs all live here; nothing else
 * records completion.
 *
 * <p>The run pins {@code scenarioKey} + {@code contentVersion} +
 * {@code contentHash}: everything it shows is read back from exactly that
 * version, checked through {@link PracticeAvailabilityService#checkRun}.</p>
 *
 * <p>JSON columns carry no {@code columnDefinition}: Hibernate maps
 * {@link SqlTypes#JSON} to {@code jsonb} on Postgres (matching V104 under
 * {@code ddl-auto: validate}) and to {@code json} on H2, so the entity also
 * builds in tests.</p>
 */
@Entity
@Getter
@Setter
@Table(name = "scenario_run")
public class ScenarioRun {

    public enum Status { IN_PROGRESS, COMPLETED, ABANDONED }

    /** One committed decision. Keys only — Practice stores no free text. */
    public record TraceStep(String nodeKey, String choiceKey, String at) {}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null for a personal run (caller has no household). */
    @Column(name = "household_id", length = 255)
    private String householdId;

    /** Who started it (lower-cased). Any household member may continue a household run. */
    @Column(name = "user_email", nullable = false, length = 320)
    private String userEmail;

    @Column(name = "scenario_key", nullable = false, length = 96)
    private String scenarioKey;

    @Column(name = "content_version", nullable = false)
    private int contentVersion;

    @Column(name = "content_hash", nullable = false, length = 128)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private Status status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "abandoned_at")
    private Instant abandonedAt;

    /** The node the run is waiting on, or the outcome it reached. */
    @Column(name = "last_node_key", length = 96)
    private String lastNodeKey;

    @Column(name = "outcome_key", length = 96)
    private String outcomeKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "decision_trace", nullable = false)
    private List<TraceStep> decisionTrace = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "debrief_tags", nullable = false)
    private List<String> debriefTags = new ArrayList<>();

    /** The {@code ReadinessAction} name of the next family step, set at completion. */
    @Column(name = "suggested_action_key", length = 96)
    private String suggestedActionKey;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (startedAt == null) startedAt = now;
        if (updatedAt == null) updatedAt = now;
        if (decisionTrace == null) decisionTrace = new ArrayList<>();
        if (debriefTags == null) debriefTags = new ArrayList<>();
    }

    @PreUpdate
    void onUpdate() {
        if (decisionTrace == null) decisionTrace = new ArrayList<>();
        if (debriefTags == null) debriefTags = new ArrayList<>();
    }
}
