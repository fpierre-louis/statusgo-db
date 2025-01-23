package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MeetingPlaceService {

    private final MeetingPlaceRepo meetingPlaceRepository;

    public MeetingPlaceService(MeetingPlaceRepo meetingPlaceRepository) {
        this.meetingPlaceRepository = meetingPlaceRepository;
    }

    public MeetingPlace saveMeetingPlace(MeetingPlace meetingPlace) {
        return meetingPlaceRepository.save(meetingPlace);
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

    public List<MeetingPlace> getMeetingPlacesByOwnerEmail(String ownerEmail) {
        return meetingPlaceRepository.findByOwnerEmail(ownerEmail);
    }
}
