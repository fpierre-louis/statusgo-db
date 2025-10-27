package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.EmergencyContact;
import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.repo.EmergencyContactGroupRepo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class EmergencyContactGroupService {

    private final EmergencyContactGroupRepo groupRepo;

    public EmergencyContactGroupService(EmergencyContactGroupRepo groupRepo) {
        this.groupRepo = groupRepo;
    }

    public List<EmergencyContactGroup> getAllGroups() {
        return groupRepo.findAll();
    }

    public List<EmergencyContactGroup> getGroupsByOwnerEmail(String ownerEmail) {
        String email = ownerEmail == null ? null : ownerEmail.trim().toLowerCase();
        if (email == null || email.isBlank()) return List.of();
        return groupRepo.findByOwnerEmailIgnoreCase(email);
    }

    public Optional<EmergencyContactGroup> getGroupById(Long id) {
        return groupRepo.findById(id);
    }

    public EmergencyContactGroup createGroup(EmergencyContactGroup group) {
        // MVP: require ownerEmail in body (since no JWT)
        String email = group.getOwnerEmail() == null ? null : group.getOwnerEmail().trim().toLowerCase();
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("ownerEmail is required");
        }
        group.setOwnerEmail(email);

        // Ensure list non-null
        if (group.getContacts() == null) group.setContacts(new ArrayList<>());

        // Link children -> parent, ensure IDs for new rows are null
        for (EmergencyContact c : group.getContacts()) {
            c.setGroup(group);
            if (c.getId() != null && String.valueOf(c.getId()).startsWith("anon-")) {
                c.setId(null);
            }
        }
        return groupRepo.save(group);
    }

    public EmergencyContactGroup updateGroup(Long id, EmergencyContactGroup updatedGroup) {
        EmergencyContactGroup existing = groupRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));

        // Update basic fields
        existing.setName(updatedGroup.getName());

        // Replace contacts with cascade + orphanRemoval
        existing.getContacts().clear();
        if (updatedGroup.getContacts() != null) {
            for (EmergencyContact c : updatedGroup.getContacts()) {
                c.setGroup(existing);
                // if client sent non-numeric temporary id (e.g., "anon-..."), force insert
                try {
                    Long.parseLong(String.valueOf(c.getId()));
                    // numeric id -> let JPA handle merge
                } catch (Exception e) {
                    c.setId(null);
                }
                existing.getContacts().add(c);
            }
        }
        return groupRepo.save(existing);
    }

    public void deleteGroup(Long id) {
        if (!groupRepo.existsById(id)) return;
        groupRepo.deleteById(id);
    }
}
