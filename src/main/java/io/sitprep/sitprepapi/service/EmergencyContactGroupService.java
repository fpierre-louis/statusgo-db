package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.repo.EmergencyContactGroupRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class EmergencyContactGroupService {

    @Autowired
    private EmergencyContactGroupRepo groupRepo;

    public List<EmergencyContactGroup> getAllGroups() {
        return groupRepo.findAll();
    }

    public List<EmergencyContactGroup> getGroupsByOwnerEmail(String ownerEmail) {
        return groupRepo.findByOwnerEmail(ownerEmail);
    }

    public Optional<EmergencyContactGroup> getGroupById(Long id) {
        return groupRepo.findById(id);
    }

    public EmergencyContactGroup createGroup(EmergencyContactGroup group) {
        return groupRepo.save(group);
    }

    public EmergencyContactGroup updateGroup(Long id, EmergencyContactGroup updatedGroup) {
        return groupRepo.findById(id).map(group -> {
            group.setName(updatedGroup.getName());
            group.setOwnerEmail(updatedGroup.getOwnerEmail());
            return groupRepo.save(group);
        }).orElseThrow(() -> new RuntimeException("Group not found"));
    }

    public void deleteGroup(Long id) {
        groupRepo.deleteById(id);
    }
}
