package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MeetingPlaceService {

    private final MeetingPlaceRepo meetingPlaceRepository;

    public MeetingPlaceService(MeetingPlaceRepo meetingPlaceRepository) {
        this.meetingPlaceRepository = meetingPlaceRepository;
    }

    @Transactional // Ensures atomic operation (all or nothing)
    public List<MeetingPlace> saveAllMeetingPlaces(String ownerEmail, List<MeetingPlace> meetingPlaces) {
        // Delete existing meeting places for the user
        meetingPlaceRepository.deleteByOwnerEmail(ownerEmail);

        // Set owner email for new records and save them
        meetingPlaces.forEach(place -> place.setOwnerEmail(ownerEmail));

        // Save and return the updated list
        return meetingPlaceRepository.saveAll(meetingPlaces);
    }

    public List<MeetingPlace> getMeetingPlacesByOwnerEmail(String ownerEmail) {
        return meetingPlaceRepository.findByOwnerEmail(ownerEmail);
    }

    public MeetingPlace updateMeetingPlace(Long id, MeetingPlace updatedPlace) {
        return meetingPlaceRepository.findById(id)
                .map(existingPlace -> {
                    existingPlace.setName(updatedPlace.getName());
                    existingPlace.setLocation(updatedPlace.getLocation());
                    existingPlace.setAddress(updatedPlace.getAddress());
                    existingPlace.setPhoneNumber(updatedPlace.getPhoneNumber());
                    existingPlace.setAdditionalInfo(updatedPlace.getAdditionalInfo());
                    existingPlace.setLat(updatedPlace.getLat());
                    existingPlace.setLng(updatedPlace.getLng());
                    existingPlace.setDeploy(updatedPlace.isDeploy());
                    return meetingPlaceRepository.save(existingPlace);
                })
                .orElseThrow(() -> new RuntimeException("Meeting place with id " + id + " not found"));
    }
}
