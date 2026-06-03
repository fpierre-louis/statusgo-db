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
    private String medicalInfo;
    private String radioChannel;

    /**
     * Optional subject this contact is specifically for. Current V1 supports
     * named manual household members only (subjectType="manual"); null means
     * household-wide. Pets remain demographic counts until they have stable ids.
     */
    private String subjectType;
    private String subjectId;
    private String subjectName;

    @ManyToOne
    @JoinColumn(name = "group_id", nullable = false)
    @JsonBackReference // ✅ Prevents infinite recursion
    private EmergencyContactGroup group;
}
