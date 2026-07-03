package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.EvacuationPlan;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.domain.UserSavedLocation;
import io.sitprep.sitprepapi.dto.MapPlaceDto;
import io.sitprep.sitprepapi.repo.EvacuationPlanRepo;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import io.sitprep.sitprepapi.repo.UserSavedLocationRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the household map's "places" layer straight from the durable
 * backend tables — the read path the frontend used to fake from localStorage
 * (gap B / gap C of docs/MAP_REBUILD_PLAN.md). Places now sync across devices
 * and survive a cache clear.
 *
 * <p>Household-owned plans (home, meeting places, shelters) are keyed by
 * {@code householdId}, falling back to the household owner's {@code ownerEmail}
 * for rows not yet backfilled by {@code HouseholdBackfillRunner}. The caller's
 * personal saved locations are added from their own account (they're personal,
 * so scoped to the caller, never fanned out to the whole household).</p>
 */
@Service
public class MapPlaceService {

    private final MeetingPlaceRepo meetingPlaceRepo;
    private final EvacuationPlanRepo evacuationPlanRepo;
    private final UserSavedLocationRepo userSavedLocationRepo;

    public MapPlaceService(MeetingPlaceRepo meetingPlaceRepo,
                           EvacuationPlanRepo evacuationPlanRepo,
                           UserSavedLocationRepo userSavedLocationRepo) {
        this.meetingPlaceRepo = meetingPlaceRepo;
        this.evacuationPlanRepo = evacuationPlanRepo;
        this.userSavedLocationRepo = userSavedLocationRepo;
    }

    @Transactional(readOnly = true)
    public List<MapPlaceDto> forHousehold(Group household, String callerEmail) {
        List<MapPlaceDto> out = new ArrayList<>();
        String hid = household.getGroupId();
        String ownerEmail = household.getOwnerEmail();

        // 1. Home / anchor — from the household Group itself.
        if (household.getLatitude() != null && household.getLongitude() != null) {
            out.add(new MapPlaceDto(
                    "group:" + hid, "house",
                    household.getLatitude(), household.getLongitude(),
                    nz(household.getGroupName(), "Home"),
                    household.getAddress(), "group"));
        }

        // 2. Meeting places — household-scoped, owner fallback for un-backfilled rows.
        List<MeetingPlace> meets = meetingPlaceRepo.findByHouseholdId(hid);
        if (meets.isEmpty() && ownerEmail != null) {
            meets = meetingPlaceRepo.findByOwnerEmail(ownerEmail);
        }
        for (MeetingPlace m : meets) {
            if (m.getLat() == null || m.getLng() == null) continue;
            out.add(new MapPlaceDto(
                    "meetup:" + m.getId(), "meetup",
                    m.getLat(), m.getLng(),
                    nz(m.getName(), "Meeting place"), m.getAddress(), "meeting_place"));
        }

        // 3. Shelters — from the evacuation plan, same household-then-owner scope.
        List<EvacuationPlan> evacs = evacuationPlanRepo.findByHouseholdId(hid);
        if (evacs.isEmpty() && ownerEmail != null) {
            evacs = evacuationPlanRepo.findByOwnerEmail(ownerEmail);
        }
        for (EvacuationPlan e : evacs) {
            if (e.getLat() == null || e.getLng() == null) continue;
            out.add(new MapPlaceDto(
                    "shelter:" + e.getId(), "shelter",
                    e.getLat(), e.getLng(),
                    nz(e.getShelterName(), "Shelter"), e.getShelterAddress(), "evacuation_plan"));
        }

        // 4. The caller's own saved places (personal — scoped to the caller only).
        if (callerEmail != null) {
            for (UserSavedLocation s :
                    userSavedLocationRepo.findByOwnerEmailIgnoreCaseOrderByIsHomeDescNameAsc(callerEmail)) {
                if (s.getLatitude() == null || s.getLongitude() == null) continue;
                out.add(new MapPlaceDto(
                        "saved:" + s.getId(), s.isHome() ? "house" : "saved",
                        s.getLatitude(), s.getLongitude(),
                        nz(s.getName(), "Saved place"), s.getAddress(), "user_saved_location"));
            }
        }

        return out;
    }

    private static String nz(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
