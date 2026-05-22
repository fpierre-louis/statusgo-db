package io.sitprep.sitprepapi.domain;

import com.fasterxml.jackson.annotation.JsonManagedReference;
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
    private String ownerEmail;

    // Owning household (Group.groupId, groupType="Household"). Nullable
    // during the ownerEmail->household migration; backfilled on boot.
    private String householdId;

    @Column(nullable = false)
    private String name;

    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JsonManagedReference // ✅ Prevents infinite recursion
    private List<EmergencyContact> contacts = new ArrayList<>();

    public void addContact(EmergencyContact contact) {
        contacts.add(contact);
        contact.setGroup(this);
    }
}
