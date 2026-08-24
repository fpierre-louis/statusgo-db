package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.EvacuationPlan;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.domain.UserSavedLocation;
import io.sitprep.sitprepapi.dto.MapPlaceDto;
import io.sitprep.sitprepapi.repo.EvacuationPlanRepo;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import io.sitprep.sitprepapi.repo.UserSavedLocationRepo;
import io.sitprep.sitprepapi.util.GeoUtil;
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

        // A PLACE THAT EXISTS IS RETURNED EVEN WHEN IT CANNOT BE DRAWN.
        //
        // Each source below used to `continue` past a row with null
        // coordinates. Nothing in this backend geocodes on write — the only
        // caller of forward geocoding is the FE-facing GeocodeResource — so an
        // address-only meeting place is the ordinary output of the evacuation
        // wizard, not an edge case. Dropping those rows made a household's own
        // saved places invisible to the map, and when every place it owned was
        // address-only the client said "No meeting places or shelters yet" —
        // a statement the record contradicts.
        //
        // Rows now carry `mappable`. The client lists what exists and pins only
        // what it can place. Do NOT geocode here to satisfy the flag: an
        // external call inside this read path would hold a DB connection open
        // across the network, and a best-effort miss would put us right back on
        // the false statement.
        //
        // The retain predicate per source is deliberately NOT "the row exists".
        // It is "the row carries something a human typed" — see the shelter
        // case below for why that distinction is load-bearing.

        // 1. Home / anchor — from the household Group itself.
        if (household.getLatitude() != null && household.getLongitude() != null) {
            out.add(place("group:" + hid, "house",
                    household.getLatitude(), household.getLongitude(),
                    nz(household.getGroupName(), "Home"),
                    household.getAddress(), "group"));
        } else if (isPresent(household.getAddress())) {
            // A household with an address but no geocode — routine, because
            // CreateHouseholdGroup clears lat/lng on every keystroke unless the
            // user picks an autocomplete suggestion.
            out.add(place("group:" + hid, "house", null, null,
                    nz(household.getGroupName(), "Home"),
                    household.getAddress(), "group"));
        }

        // 2. Meeting places — household-scoped, owner fallback for un-backfilled rows.
        List<MeetingPlace> meets = meetingPlaceRepo.findByHouseholdId(hid);
        if (meets.isEmpty() && ownerEmail != null) {
            meets = meetingPlaceRepo.findByOwnerEmail(ownerEmail);
        }
        for (MeetingPlace m : meets) {
            if (!isPresent(m.getName()) && !isPresent(m.getAddress())
                    && (m.getLat() == null || m.getLng() == null)) {
                continue; // an empty row is not a place
            }
            out.add(place("meetup:" + m.getId(), "meetup",
                    m.getLat(), m.getLng(),
                    nz(m.getName(), "Meeting place"), m.getAddress(), "meeting_place"));
        }

        // 3. Shelters — from the evacuation plan, same household-then-owner scope.
        //
        // THE PREDICATE HERE IS THE SHARP EDGE. EvacuationPlanService
        // .updateRouteNotes creates a plan row with no shelter fields whenever
        // a household saves route notes and has no prior plan. Testing the
        // nz()-defaulted name would make every such household sprout a phantom
        // place literally named "Shelter". So the test is against the RAW
        // entity fields, before any defaulting.
        List<EvacuationPlan> evacs = evacuationPlanRepo.findByHouseholdId(hid);
        if (evacs.isEmpty() && ownerEmail != null) {
            evacs = evacuationPlanRepo.findByOwnerEmail(ownerEmail);
        }
        for (EvacuationPlan e : evacs) {
            boolean hasCoords = e.getLat() != null && e.getLng() != null;
            boolean namedByAHuman = isPresent(e.getShelterName()) || isPresent(e.getShelterAddress());
            if (!hasCoords && !namedByAHuman) continue;
            out.add(place("shelter:" + e.getId(), "shelter",
                    e.getLat(), e.getLng(),
                    nz(e.getShelterName(), "Shelter"), e.getShelterAddress(), "evacuation_plan"));
        }

        // 4. The caller's own saved places (personal — scoped to the caller only).
        //    UserSavedLocation's coordinate columns are NOT NULL and create()
        //    rejects an invalid pair, so these are always mappable; the guard
        //    stays as a belt-and-braces check rather than a live case.
        if (callerEmail != null) {
            for (UserSavedLocation s :
                    userSavedLocationRepo.findByOwnerEmailIgnoreCaseOrderByIsHomeDescNameAsc(callerEmail)) {
                if (s.getLatitude() == null || s.getLongitude() == null) continue;
                out.add(place("saved:" + s.getId(), s.isHome() ? "house" : "saved",
                        s.getLatitude(), s.getLongitude(),
                        nz(s.getName(), "Saved place"), s.getAddress(), "user_saved_location"));
            }
        }

        return out;
    }

    /** Builds a place row, deriving `mappable` from the coordinates it was given. */
    private static MapPlaceDto place(String id, String kind, Double lat, Double lng,
                                     String name, String address, String source) {
        return new MapPlaceDto(id, kind, lat, lng, name, address, source,
                GeoUtil.validLatLng(lat, lng));
    }

    private static boolean isPresent(String v) {
        return v != null && !v.isBlank();
    }

    private static String nz(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
