package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * What one person needs a household to remember about them during an emergency.
 *
 * <p><b>This is a preparedness profile, not a medical record.</b> Every field
 * had to answer one question to be here: would knowing this materially change
 * how this person prepares, evacuates, shelters, communicates, reunifies or
 * receives emergency assistance? Diagnoses, medication schedules, allergies,
 * physicians, insurance and appointments all fail that test and are permanently
 * out of scope — see {@code docs/architecture/EMERGENCY_SUPPORT_MODEL.md} and
 * the anti-vision in {@code VISION_AND_SCOPE.md}.</p>
 *
 * <p><b>The row IS the flag.</b> There is no {@code needsEmergencySupport}
 * boolean on {@code UserInfo} or {@code HouseholdManualMember}: a profile
 * existing means this person needs support, and a denormalized copy on two
 * entities would be two more migrations plus a value that can drift from the
 * row it describes. Rosters derive the flag from one query per household view.</p>
 *
 * <p><b>Subject, not owner.</b> {@code subjectType}/{@code subjectId} follows
 * the convention {@link EmergencyContact} already ships, so app members and
 * manual members (children, elders, anyone without a phone) share one shape.
 * The household owns the row either way — a manual member cannot edit their own,
 * and that is the same authority model as the rest of their record.</p>
 */
@Entity
@Table(
        name = "emergency_support_profile",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_support_profile_subject",
                columnNames = {"household_id", "subject_type", "subject_id"}
        )
)
public class EmergencySupportProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "household_id", nullable = false, length = 64)
    private String householdId;

    /** {@code user} | {@code manual} — matches EmergencyContact's convention. */
    @Column(name = "subject_type", nullable = false, length = 16)
    private String subjectType;

    @Column(name = "subject_id", nullable = false, length = 255)
    private String subjectId;

    // ── Mobility & evacuation ────────────────────────────────────────────
    /** Cannot get out unaided. The single most operationally important fact here. */
    @Column(name = "needs_evacuation_assistance", nullable = false)
    private boolean needsEvacuationAssistance = false;

    @Column(name = "cannot_use_stairs", nullable = false)
    private boolean cannotUseStairs = false;

    /**
     * Short and operational — "walker", "300lb power wheelchair". A device
     * TAXONOMY would not change the decision any further than this does; what
     * changes the decision is whether it fits in the car.
     */
    @Column(name = "mobility_note", length = 120)
    private String mobilityNote;

    // ── Communication & accessibility ────────────────────────────────────
    /**
     * How to reach and be understood by this person, as operational values
     * rather than diagnoses — see {@code CommunicationNeed}. Deaf, low-vision,
     * limited-English and cognitive-support needs are all representable by what
     * a helper must DO differently, without SitPrep recording why.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "emergency_support_communication_need",
            joinColumns = @JoinColumn(name = "profile_id")
    )
    @Column(name = "need", length = 40)
    private List<String> communicationNeeds = new ArrayList<>();

    /** Only meaningful alongside INTERPRETER_OR_LANGUAGE. */
    @Column(name = "preferred_language", length = 40)
    private String preferredLanguage;

    // ── Equipment & power ────────────────────────────────────────────────
    @Column(name = "power_dependent_equipment", nullable = false)
    private boolean powerDependentEquipment = false;

    /** "oxygen concentrator", "night ventilator". Never settings or serials. */
    @Column(name = "equipment_note", length = 120)
    private String equipmentNote;

    /**
     * Needs cold storage. A DEPENDENCY, never a schedule: "requires refrigerated
     * insulin during an evacuation" is in scope; "the 2pm dose is due" is not.
     */
    @Column(name = "refrigerated_medication", nullable = false)
    private boolean refrigeratedMedication = false;

    /** Medication that must travel with them. Again a dependency, not a regimen. */
    @Column(name = "critical_medication", nullable = false)
    private boolean criticalMedication = false;

    // ── Transportation ───────────────────────────────────────────────────
    @Column(name = "accessible_transport_needed", nullable = false)
    private boolean accessibleTransportNeeded = false;

    // ── Service animal ───────────────────────────────────────────────────
    /**
     * A trained service animal is NOT a pet and deliberately does not share
     * {@link HouseholdPet}: it travels with the person, is admitted where pets
     * are not, and losing that distinction loses the reason to record it.
     */
    @Column(name = "service_animal", nullable = false)
    private boolean serviceAnimal = false;

    @Column(name = "service_animal_note", length = 120)
    private String serviceAnimalNote;

    // ── One short note, constrained by purpose ───────────────────────────
    /**
     * What a helper needs in the first ten minutes — "panics at sirens, respond
     * calmly and use his name". 240 characters is a deliberate ceiling: enough
     * for an instruction, not enough for a chart.
     */
    @Column(name = "support_note", length = 240)
    private String supportNote;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by_email", length = 255)
    private String updatedByEmail;

    @PrePersist
    @PreUpdate
    void stampUpdatedAt() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }

    public String getHouseholdId() { return householdId; }
    public void setHouseholdId(String v) { this.householdId = v; }

    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String v) { this.subjectType = v; }

    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String v) { this.subjectId = v; }

    public boolean isNeedsEvacuationAssistance() { return needsEvacuationAssistance; }
    public void setNeedsEvacuationAssistance(boolean v) { this.needsEvacuationAssistance = v; }

    public boolean isCannotUseStairs() { return cannotUseStairs; }
    public void setCannotUseStairs(boolean v) { this.cannotUseStairs = v; }

    public String getMobilityNote() { return mobilityNote; }
    public void setMobilityNote(String v) { this.mobilityNote = v; }

    public List<String> getCommunicationNeeds() { return communicationNeeds; }
    public void setCommunicationNeeds(List<String> v) {
        this.communicationNeeds = v == null ? new ArrayList<>() : new ArrayList<>(v);
    }

    public String getPreferredLanguage() { return preferredLanguage; }
    public void setPreferredLanguage(String v) { this.preferredLanguage = v; }

    public boolean isPowerDependentEquipment() { return powerDependentEquipment; }
    public void setPowerDependentEquipment(boolean v) { this.powerDependentEquipment = v; }

    public String getEquipmentNote() { return equipmentNote; }
    public void setEquipmentNote(String v) { this.equipmentNote = v; }

    public boolean isRefrigeratedMedication() { return refrigeratedMedication; }
    public void setRefrigeratedMedication(boolean v) { this.refrigeratedMedication = v; }

    public boolean isCriticalMedication() { return criticalMedication; }
    public void setCriticalMedication(boolean v) { this.criticalMedication = v; }

    public boolean isAccessibleTransportNeeded() { return accessibleTransportNeeded; }
    public void setAccessibleTransportNeeded(boolean v) { this.accessibleTransportNeeded = v; }

    public boolean isServiceAnimal() { return serviceAnimal; }
    public void setServiceAnimal(boolean v) { this.serviceAnimal = v; }

    public String getServiceAnimalNote() { return serviceAnimalNote; }
    public void setServiceAnimalNote(String v) { this.serviceAnimalNote = v; }

    public String getSupportNote() { return supportNote; }
    public void setSupportNote(String v) { this.supportNote = v; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }

    public String getUpdatedByEmail() { return updatedByEmail; }
    public void setUpdatedByEmail(String v) { this.updatedByEmail = v; }
}
