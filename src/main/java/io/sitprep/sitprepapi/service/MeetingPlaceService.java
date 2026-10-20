package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.gamification.TokenEventPublisher;
import io.sitprep.sitprepapi.gamification.TokenEventType;
import io.sitprep.sitprepapi.util.PlanRowReconciler;
import io.sitprep.sitprepapi.util.GeoUtil;
import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.domain.MeetingPlaceTier;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import io.sitprep.sitprepapi.util.AuthUtils;
import io.sitprep.sitprepapi.util.MainChoice;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MeetingPlaceService {

    private final MeetingPlaceRepo meetingPlaceRepository;
    private final HouseholdResolver householdResolver;
    private final ActivationPlanUpdateBroadcastService activationPlanUpdates;
    private final TokenEventPublisher tokenEvents;

    public MeetingPlaceService(MeetingPlaceRepo meetingPlaceRepository,
                               HouseholdResolver householdResolver,
                               ActivationPlanUpdateBroadcastService activationPlanUpdates,
        TokenEventPublisher tokenEvents) {
        this.tokenEvents = tokenEvents;
        this.meetingPlaceRepository = meetingPlaceRepository;
        this.householdResolver = householdResolver;
        this.activationPlanUpdates = activationPlanUpdates;
    }

    public static MeetingPlaceTier inferMeetingTier(String tierKey) {
        if (tierKey == null || tierKey.isBlank()) return MeetingPlaceTier.OTHER;
        return switch (tierKey.trim().toLowerCase()) {
            case "safe_room", "indoor_safe_room" -> MeetingPlaceTier.INDOOR_SAFE_ROOM;
            case "near_home", "neighborhood", "outside_home" -> MeetingPlaceTier.OUTSIDE_HOME;
            case "in_town", "out_of_area", "out_of_town" -> MeetingPlaceTier.OUT_OF_TOWN;
            default -> MeetingPlaceTier.OTHER;
        };
    }

    // Setter-injected so tests that build this service by hand stay unchanged;
    // null there, and then only a row's own author may set its Main.
    private HouseholdAccessService householdAccess;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setHouseholdAccess(HouseholdAccessService householdAccess) {
        this.householdAccess = householdAccess;
    }

    /**
     * The indoor safe room is where a household shelters in place, not where it
     * regroups (owner decision D2, 2026-10-20), so it never holds Main.
     */
    static boolean canBeMain(MeetingPlace p) {
        return p.getMeetingTier() != MeetingPlaceTier.INDOOR_SAFE_ROOM;
    }

    /** One Main across a list about to be saved (bulk saves replace the set). */
    private static void keepOneMain(List<MeetingPlace> places) {
        MainChoice.keepOne(places, MeetingPlace::getId, MeetingPlace::isDeploy,
                MeetingPlace::setDeploy, MeetingPlaceService::canBeMain, null);
    }

    /** After a single-row write that left {@code kept} as Main, clear its siblings. */
    private void clearOtherMains(MeetingPlace kept) {
        if (kept == null || !kept.isDeploy()) return;
        List<MeetingPlace> siblings = kept.getHouseholdId() != null
                ? meetingPlaceRepository.findByHouseholdId(kept.getHouseholdId())
                : meetingPlaceRepository.findByOwnerEmail(kept.getOwnerEmail());
        List<MeetingPlace> changed = MainChoice.keepOne(siblings, MeetingPlace::getId, MeetingPlace::isDeploy,
                MeetingPlace::setDeploy, MeetingPlaceService::canBeMain, kept.getId());
        if (!changed.isEmpty()) meetingPlaceRepository.saveAll(changed);
    }

    /**
     * Make one meeting spot the household's Main and clear the rest. The row's
     * author or a household owner/admin may do it.
     */
    @Transactional
    public MeetingPlace setMain(Long id, String caller) {
        MeetingPlace place = meetingPlaceRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Meeting spot not found"));
        boolean author = place.getOwnerEmail() != null && place.getOwnerEmail().equalsIgnoreCase(caller);
        boolean admin = householdAccess != null && place.getHouseholdId() != null
                && householdAccess.canWriteHousehold(caller, place.getHouseholdId());
        if (!author && !admin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the household's admins can choose its main meeting spot");
        }
        normalizeMeetingTier(place);
        if (!canBeMain(place)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The safe room is for staying home, not a meeting spot");
        }
        place.setDeploy(true);
        MeetingPlace saved = meetingPlaceRepository.save(place);
        clearOtherMains(saved);
        activationPlanUpdates.broadcastOwnerPlanChangedAfterCommit(saved.getOwnerEmail(), "meetingPlaces");
        return saved;
    }

    private static void normalizeMeetingTier(MeetingPlace place) {
        if (place.getMeetingTier() == null || place.getMeetingTier() == MeetingPlaceTier.OTHER) {
            place.setMeetingTier(inferMeetingTier(place.getTierKey()));
        }
    }

    @Transactional
    public List<MeetingPlace> saveAllMeetingPlaces(List<MeetingPlace> meetingPlaces) {
        String ownerEmail = AuthUtils.getCurrentUserEmail();
        meetingPlaces.forEach(p -> {
            GeoUtil.requireValidLatLng(p.getLat(), p.getLng());
            normalizeMeetingTier(p);
        });
        keepOneMain(meetingPlaces);

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
        tokenEvents.householdsOf(TokenEventType.MEETING_PLACE_CHANGED, saved, MeetingPlace::getHouseholdId);
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
                    existingPlace.setTierKey(updatedPlace.getTierKey());
                    existingPlace.setMeetingTier(updatedPlace.getMeetingTier());
                    existingPlace.setAdditionalInfo(updatedPlace.getAdditionalInfo());
                    GeoUtil.requireValidLatLng(updatedPlace.getLat(), updatedPlace.getLng());
                    existingPlace.setLat(updatedPlace.getLat());
                    existingPlace.setLng(updatedPlace.getLng());
                    normalizeMeetingTier(existingPlace);
                    existingPlace.setDeploy(updatedPlace.isDeploy() && canBeMain(existingPlace));
                    if (existingPlace.getHouseholdId() == null) {
                        existingPlace.setHouseholdId(
                                householdResolver.baseHouseholdIdFor(existingPlace.getOwnerEmail()));
                    }

                    MeetingPlace saved = meetingPlaceRepository.save(existingPlace);
                    clearOtherMains(saved);
                    activationPlanUpdates.broadcastOwnerPlanChangedAfterCommit(
                            existingPlace.getOwnerEmail(), "meetingPlaces");
                    tokenEvents.household(TokenEventType.MEETING_PLACE_CHANGED, saved.getHouseholdId(), saved.getId());
                    return saved;
                })
                .orElseThrow(() -> new RuntimeException("Meeting place with id " + id + " not found"));
    }

    @Transactional
    public List<MeetingPlace> saveAllMeetingPlaces(String ownerEmail, List<MeetingPlace> places) {
        places.forEach(p -> {
            GeoUtil.requireValidLatLng(p.getLat(), p.getLng());
            normalizeMeetingTier(p);
        });
        keepOneMain(places);
        // Cross-household edit (X-Household-Id, admin of that household):
        // replace THAT household's meeting places + stamp it. Else unchanged.
        String target = householdResolver.writableTargetHousehold(ownerEmail);
        if (target != null) {
            meetingPlaceRepository.deleteAll(PlanRowReconciler.reconcile(
                    meetingPlaceRepository.findByHouseholdId(target), places,
                    MeetingPlace::getId, MeetingPlace::setId));
            places.forEach(place -> {
                place.setOwnerEmail(ownerEmail);
                place.setHouseholdId(target);
            });
            List<MeetingPlace> saved = meetingPlaceRepository.saveAll(places);
            activationPlanUpdates.broadcastOwnerPlanChangedAfterCommit(ownerEmail, "meetingPlaces");
            tokenEvents.householdsOf(TokenEventType.MEETING_PLACE_CHANGED, saved, MeetingPlace::getHouseholdId);
            return saved;
        }
        // Reconcile against the HOUSEHOLD's rows (what the reads now show),
        // falling back to the caller's own for an un-backfilled account — else
        // a member saving the household's places would duplicate them.
        String householdId = householdResolver.baseHouseholdIdFor(ownerEmail);
        List<MeetingPlace> current = householdId == null ? List.of() : meetingPlaceRepository.findByHouseholdId(householdId);
        if (current.isEmpty()) current = meetingPlaceRepository.findByOwnerEmail(ownerEmail);
        meetingPlaceRepository.deleteAll(PlanRowReconciler.reconcile(
                current, places, MeetingPlace::getId, MeetingPlace::setId));
        places.forEach(place -> {
            place.setOwnerEmail(ownerEmail);
            if (place.getHouseholdId() == null) place.setHouseholdId(householdId);
        });
        List<MeetingPlace> saved = meetingPlaceRepository.saveAll(places);
        activationPlanUpdates.broadcastOwnerPlanChangedAfterCommit(ownerEmail, "meetingPlaces");
        tokenEvents.householdsOf(TokenEventType.MEETING_PLACE_CHANGED, saved, MeetingPlace::getHouseholdId);
        return saved;
    }

    public List<MeetingPlace> getMeetingPlacesByOwnerEmail(String ownerEmail) {
        // Household-first, author-email fallback (open-items plan 3.1): read by
        // the author's email, a household member who didn't write the plan saw
        // none of it — the writes stamp householdId.
        String hid = householdResolver.baseHouseholdIdFor(ownerEmail);
        List<MeetingPlace> mine = hid == null ? List.of() : meetingPlaceRepository.findByHouseholdId(hid);
        return mine.isEmpty() ? meetingPlaceRepository.findByOwnerEmail(ownerEmail) : mine;
    }

    /**
     * Create a single meeting place WITHOUT deleting the owner's existing
     * ones (unlike the bulk save). Used by the activation surface's
     * "add another spot" so adding one doesn't clobber the rest.
     */
    @Transactional
    public MeetingPlace addMeetingPlace(MeetingPlace place) {
        GeoUtil.requireValidLatLng(place.getLat(), place.getLng());
        normalizeMeetingTier(place);
        if (!canBeMain(place)) place.setDeploy(false);
        if (place.getHouseholdId() == null) {
            String target = householdResolver.writableTargetHousehold(place.getOwnerEmail());
            place.setHouseholdId(target != null
                    ? target
                    : householdResolver.baseHouseholdIdFor(place.getOwnerEmail()));
        }
        MeetingPlace saved = meetingPlaceRepository.save(place);
        clearOtherMains(saved);
        activationPlanUpdates.broadcastOwnerPlanChangedAfterCommit(place.getOwnerEmail(), "meetingPlaces");
        tokenEvents.household(TokenEventType.MEETING_PLACE_CHANGED, saved.getHouseholdId(), saved.getId());
        return saved;
    }

}
