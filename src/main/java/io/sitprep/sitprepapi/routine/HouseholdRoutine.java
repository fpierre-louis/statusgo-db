package io.sitprep.sitprepapi.routine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A household's saved if-then routine (V124, EXEC-R1): plan data, never practice history.
 * The rendered {@code ifText}/{@code thenText} are a snapshot of what the household saved.
 * No run, session, role, turn, child or score is stored here, by design.
 */
@Entity
@Getter
@Setter
@Table(name = "household_routine", uniqueConstraints = @UniqueConstraint(
        name = "uk_household_routine_household_key", columnNames = {"household_id", "routine_key"}))
public class HouseholdRoutine {

    /** One chosen slot value; {@code refId} optionally points at the plan item it came from. */
    public record SlotValue(String value, String refId) {}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "household_id", nullable = false, length = 255)
    private String householdId;

    @Column(name = "routine_key", nullable = false, length = 96)
    private String routineKey;

    @Column(name = "content_version", nullable = false)
    private int contentVersion;

    @Column(name = "hazard_key", nullable = false, length = 48)
    private String hazardKey;

    @Column(name = "if_text", nullable = false, length = 200)
    private String ifText;

    @Column(name = "then_text", nullable = false, length = 300)
    private String thenText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "slot_values", nullable = false)
    private Map<String, SlotValue> slotValues = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by_email", length = 255)
    private String createdByEmail;

    @Column(name = "updated_by_email", length = 255)
    private String updatedByEmail;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;
}
