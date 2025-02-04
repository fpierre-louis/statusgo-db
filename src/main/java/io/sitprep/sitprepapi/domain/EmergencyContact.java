package io.sitprep.sitprepapi.domain;

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

    @ManyToOne
    @JoinColumn(name = "group_id", nullable = false)
    private EmergencyContactGroup group;
}
