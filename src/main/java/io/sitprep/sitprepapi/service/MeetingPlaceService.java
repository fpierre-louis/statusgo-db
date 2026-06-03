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
    private final HouseholdResolver householdResolver;
    private final ActivationPlanUpdateBroadcastService activationPlanUpdates;

    public MeetingPlaceService(MeetingPlaceRepo meetingPlaceRepository,
                               HouseholdResolver householdResolver,
                               ActivationPlanUpdateBroadcastService activationPlanUpdates) {
        this.meetingPlaceRepository = meetingPlaceRepository;
        this.householdResolver = householdResolver;
        this.activationPlanUpdates = activationPlanUpdates;
    }

    @Transactional
    public List<MeetingPlace> saveAllMeetingPlaces(List<MeetingPlace> meetingPlaces) {
        String ownerEmail = AuthUtils.getCurrentUserEmail();

        // Delete existing meeting places for the user
        meetingPlaceRepository.deleteByOwnerEmail(ownerEmail);

        // Set authenticated owner email + owning household, then save.
        String householdId = householdResolver.baseHouseholdIdFor(ownerEmail);
        meetingPlaces.forEach(place -> {
            place.setOwnerEmail(ownerEmail);
            if (place.getHouseholdId() == null) place.setHouseholdId(householdId);
        });

        List<MeetingPlace> saved = meetingPlaceRepository.saveAll(meetingPlaces);
        activationPlanUpdates.broadcastOwnerPlanChangedAfterCommit(ownerEmail, "meetingPlaces");
        return saved;
    }

    public List<MeetingPlace> getMeetingPlacesForCurrentUser() {
        String ownerEmail = AuthUtils.getCurrentUserEmail();
        return meetingPlaceRepository.findByOwnerEmail(ownerEmail);
    }

    @Transactional
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
                    if (existingPlace.getHouseholdId() == null) {
                        existingPlace.setHouseholdId(
                                householdResolver.baseHouseholdIdFor(existingPlace.getOwnerEmail()));
                    }

                    MeetingPlace saved = meetingPlaceRepository.save(existingPlace);
                    activationPlanUpdates.broadcastOwnerPlanChangedAfterCommit(
                            existingPlace.getOwnerEmail(), "meetingPlaces");
                    return saved;
                })
                .orElseThrow(() -> new RuntimeException("Meeting place with id " + id + " not found"));
    }

    @Transactional
    public List<MeetingPlace> saveAllMeetingPlaces(String ownerEmail, List<MeetingPlace> places) {
        // Cross-household edit (X-Household-Id, admin of that household):
        // replace THAT household's meeting places + stamp it. Else unchanged.
        String target = householdResolver.writableTargetHousehold(ownerEmail);
        if (target != null) {
            meetingPlaceRepository.deleteAll(meetingPlaceRepository.findByHouseholdId(target));
            places.forEach(place -> {
                place.setOwnerEmail(ownerEmail);
                place.setHouseholdId(target);
            });
            List<MeetingPlace> saved = meetingPlaceRepository.saveAll(places);
            activationPlanUpdates.broadcastOwnerPlanChangedAfterCommit(ownerEmail, "meetingPlaces");
            return saved;
        }
        meetingPlaceRepository.deleteAll(meetingPlaceRepository.findByOwnerEmail(ownerEmail));
        String householdId = householdResolver.baseHouseholdIdFor(ownerEmail);
        places.forEach(place -> {
            place.setOwnerEmail(ownerEmail);
            if (place.getHouseholdId() == null) place.setHouseholdId(householdId);
        });
        List<MeetingPlace> saved = meetingPlaceRepository.saveAll(places);
        activationPlanUpdates.broadcastOwnerPlanChangedAfterCommit(ownerEmail, "meetingPlaces");
        return saved;
    }

    public List<MeetingPlace> getMeetingPlacesByOwnerEmail(String ownerEmail) {
        return meetingPlaceRepository.findByOwnerEmail(ownerEmail);
    }

    /**
     * Create a single meeting place WITHOUT deleting the owner's existing
     * ones (unlike the bulk save). Used by the activation surface's
     * "add another spot" so adding one doesn't clobber the rest.
     */
    @Transactional
    public MeetingPlace addMeetingPlace(MeetingPlace place) {
        if (place.getHouseholdId() == null) {
            String target = householdResolver.writableTargetHousehold(place.getOwnerEmail());
            place.setHouseholdId(target != null
                    ? target
                    : householdResolver.baseHouseholdIdFor(place.getOwnerEmail()));
        }
        MeetingPlace saved = meetingPlaceRepository.save(place);
        activationPlanUpdates.broadcastOwnerPlanChangedAfterCommit(place.getOwnerEmail(), "meetingPlaces");
        return saved;
    }

}
