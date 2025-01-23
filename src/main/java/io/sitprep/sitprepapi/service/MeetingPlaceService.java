package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import org.springframework.stereotype.Service;

import java.util.List;

import org.springframework.transaction.annotation.Transactional;

@Service
public class MeetingPlaceService {

    private final MeetingPlaceRepo meetingPlaceRepo;

    public MeetingPlaceService(MeetingPlaceRepo meetingPlaceRepo) {
        this.meetingPlaceRepo = meetingPlaceRepo;
    }

    @Transactional // Ensure all methods are executed within a transaction
    public List<MeetingPlace> getMeetingPlacesByOwnerEmail(String ownerEmail) {
        return meetingPlaceRepo.findByOwnerEmail(ownerEmail);
    }

    @Transactional
    public MeetingPlace saveMeetingPlace(MeetingPlace meetingPlace) {
        return meetingPlaceRepo.save(meetingPlace);
    }

    @Transactional
    public void deleteMeetingPlace(Long id) {
        meetingPlaceRepo.deleteById(id);
    }
}
