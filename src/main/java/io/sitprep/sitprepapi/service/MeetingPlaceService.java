package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MeetingPlaceService {

    private final MeetingPlaceRepo meetingPlaceRepo;

    public MeetingPlaceService(MeetingPlaceRepo meetingPlaceRepo) {
        this.meetingPlaceRepo = meetingPlaceRepo;
    }

    public MeetingPlace saveMeetingPlace(MeetingPlace meetingPlace) {
        return meetingPlaceRepo.save(meetingPlace);
    }

    public List<MeetingPlace> getMeetingPlacesByOwnerEmail(String ownerEmail) {
        return meetingPlaceRepo.findByOwnerEmail(ownerEmail);
    }

    public void deleteMeetingPlace(Long id) {
        meetingPlaceRepo.deleteById(id);
    }
}
