package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MeetingPlaceService {

    private final MeetingPlaceRepo meetingPlaceRepository;

    public MeetingPlaceService(MeetingPlaceRepo meetingPlaceRepository) {
        this.meetingPlaceRepository = meetingPlaceRepository;
    }

    @Transactional
    public List<MeetingPlace> saveAllMeetingPlaces(List<MeetingPlace> meetingPlaces) {
        String ownerEmail = AuthUtils.getCurrentUserEmail();

        // Delete existing meeting places for the user
        meetingPlaceRepository.deleteByOwnerEmail(ownerEmail);

        // Set authenticated owner email and save
        meetingPlaces.forEach(place -> place.setOwnerEmail(ownerEmail));

        return meetingPlaceRepository.saveAll(meetingPlaces);
    }

    public List<MeetingPlace> getMeetingPlacesForCurrentUser() {
        String ownerEmail = AuthUtils.getCurrentUserEmail();
        return meetingPlaceRepository.findByOwnerEmail(ownerEmail);
    }

    public MeetingPlace updateMeetingPlace(Long id, MeetingPlace updatedPlace) {
        String currentUser = AuthUtils.getCurrentUserEmail();

        return meetingPlaceRepository.findById(id)
                .map(existingPlace -> {
                    if (!existingPlace.getOwnerEmail().equalsIgnoreCase(currentUser)) {
                        throw new SecurityException("Unauthorized to update this meeting place.");
                    }

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

    public List<MeetingPlace> saveAllMeetingPlaces(String ownerEmail, List<MeetingPlace> places) {
        meetingPlaceRepository.deleteAll(meetingPlaceRepository.findByOwnerEmail(ownerEmail));
        places.forEach(place -> place.setOwnerEmail(ownerEmail));
        return meetingPlaceRepository.saveAll(places);
    }

    public List<MeetingPlace> getMeetingPlacesByOwnerEmail(String ownerEmail) {
        return meetingPlaceRepository.findByOwnerEmail(ownerEmail);
    }

}
