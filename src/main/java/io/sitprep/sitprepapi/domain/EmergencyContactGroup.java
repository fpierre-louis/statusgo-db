package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "emergency_contact_groups")
public class EmergencyContactGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String ownerEmail; // ✅ New field to associate groups with users

    @Column(nullable = false)
    private String name;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, mappedBy = "group")
    private List<EmergencyContact> contacts = new ArrayList<>();

    public void addContact(EmergencyContact contact) {
        contacts.add(contact);
        contact.setGroup(this);
    }

    public void removeContact(EmergencyContact contact) {
        contacts.remove(contact);
        contact.setGroup(null);
    }
}
