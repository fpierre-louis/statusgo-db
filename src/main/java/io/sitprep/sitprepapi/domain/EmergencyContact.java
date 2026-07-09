package io.sitprep.sitprepapi.domain;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "emergency_contacts")
public class EmergencyContact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String phone;
    private String email;
    private String address;
    private String role;
    @Enumerated(EnumType.STRING)
    @Column(name = "contact_type", nullable = false, length = 32)
    private EmergencyContactType contactType = EmergencyContactType.OTHER;
    private String medicalInfo;
    private String radioChannel;

    /**
     * Optional subject this contact is specifically for. Current V1 supports
     * named manual household members (subjectType="manual") and named pets
     * (subjectType="pet"); null means household-wide.
     */
    private String subjectType;
    private String subjectId;
    private String subjectName;

    @ManyToOne
    @JoinColumn(name = "group_id", nullable = false)
    @JsonBackReference // ✅ Prevents infinite recursion
    private EmergencyContactGroup group;
}
