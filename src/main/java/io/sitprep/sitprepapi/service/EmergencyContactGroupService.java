package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.EmergencyContact;
import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.repo.EmergencyContactGroupRepo;
import io.sitprep.sitprepapi.util.AuthUtils;
import io.sitprep.sitprepapi.util.OwnershipValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class EmergencyContactGroupService {

    @Autowired
    private EmergencyContactGroupRepo groupRepo;

    public List<EmergencyContactGroup> getGroupsForCurrentUser() {
        String currentEmail = AuthUtils.getCurrentUserEmail();
        return groupRepo.findByOwnerEmail(currentEmail);
    }

    public Optional<EmergencyContactGroup> getGroupById(Long id) {
        EmergencyContactGroup group = groupRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Group not found"));

        OwnershipValidator.requireOwnerEmailMatch(group.getOwnerEmail());
        return Optional.of(group);
    }

    public EmergencyContactGroup createGroup(EmergencyContactGroup group) {
        String currentEmail = AuthUtils.getCurrentUserEmail();
        group.setOwnerEmail(currentEmail);

        for (EmergencyContact contact : group.getContacts()) {
            contact.setGroup(group); // ✅ Link each contact correctly
        }

        return groupRepo.save(group);
    }

    public EmergencyContactGroup updateGroup(Long id, EmergencyContactGroup updatedGroup) {
        return groupRepo.findById(id).map(group -> {
            OwnershipValidator.requireOwnerEmailMatch(group.getOwnerEmail());

            group.setName(updatedGroup.getName());

            // ✅ Clear and re-add contacts
            group.getContacts().clear();
            for (EmergencyContact contact : updatedGroup.getContacts()) {
                contact.setGroup(group);
                group.getContacts().add(contact);
            }

            return groupRepo.save(group);
        }).orElseThrow(() -> new RuntimeException("Group not found"));
    }

    public void deleteGroup(Long id) {
        EmergencyContactGroup group = groupRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Group not found"));

        OwnershipValidator.requireOwnerEmailMatch(group.getOwnerEmail());
        groupRepo.deleteById(id);
    }

    public List<EmergencyContactGroup> getAllGroups() {
        return groupRepo.findAll();
    }

    public List<EmergencyContactGroup> getGroupsByOwnerEmail(String ownerEmail) {
        return groupRepo.findByOwnerEmail(ownerEmail);
    }

}
