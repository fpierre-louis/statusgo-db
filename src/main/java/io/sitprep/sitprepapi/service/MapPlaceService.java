package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.EvacuationPlan;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.domain.OriginLocation;
import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.dto.MapPlaceDto;
import io.sitprep.sitprepapi.repo.EvacuationPlanRepo;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import io.sitprep.sitprepapi.repo.OriginLocationRepo;
import io.sitprep.sitprepapi.repo.PlanActivationRepo;
import io.sitprep.sitprepapi.util.GeoUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Assembles the household map's PLAN places — what the household would use in
 * an emergency, not everything it ever saved (plan-locations audit 2026-09-29,
 * owner rulings Q-1..Q-4).
 *
 * <ul>
 *   <li><b>A plan is deployed</b> (a live {@code PlanActivation} for this
 *       household): the meeting place and the shelter THAT DEPLOYMENT SELECTED
 *       ({@code role = selected-meeting | selected-shelter}), and nothing it
 *       didn't select.</li>
 *   <li><b>No plan deployed:</b> the plan's primary meeting place and primary
 *       shelter ({@code role = meeting | shelter}) — the one marked to use
 *       ({@code deploy}), else the first by tier.</li>
 *   <li><b>Always:</b> the household home ({@code role = home}) and the plan's
 *       starting points — Home, Work, Kids' school as entered in the plan
 *       ({@code kind = start}, {@code role = start}); one that is the home
 *       itself is not drawn twice.</li>
 * </ul>
 *
 * <p>Members' PERSONAL saved places left this feed (Q-3): they are opt-in
 * presence, not the plan.</p>
 *
 * <p>Household-owned plans are keyed by {@code householdId}, falling back to
 * the household owner's {@code ownerEmail} for rows not yet backfilled by
 * {@code HouseholdBackfillRunner}.</p>
 */
@Service
public class MapPlaceService {

    /** A starting point this close to the home IS the home — drawn once. */
    private static final double SAME_PLACE_KM = 0.1;

    private final MeetingPlaceRepo meetingPlaceRepo;
    private final EvacuationPlanRepo evacuationPlanRepo;
    private final OriginLocationRepo originLocationRepo;
    private final PlanActivationRepo activationRepo;

    public MapPlaceService(MeetingPlaceRepo meetingPlaceRepo,
                           EvacuationPlanRepo evacuationPlanRepo,
                           OriginLocationRepo originLocationRepo,
                           PlanActivationRepo activationRepo) {
        this.meetingPlaceRepo = meetingPlaceRepo;
        this.evacuationPlanRepo = evacuationPlanRepo;
        this.originLocationRepo = originLocationRepo;
        this.activationRepo = activationRepo;
    }

    @Transactional(readOnly = true)
    public List<MapPlaceDto> forHousehold(Group household, String callerEmail) {
        List<MapPlaceDto> out = new ArrayList<>();
        String hid = household.getGroupId();

        // THE OWNER-EMAIL FALLBACK ONLY APPLIES TO AN ACTUAL HOUSEHOLD.
        //
        // The fallbacks below exist for household rows predating
        // HouseholdBackfillRunner, which have no householdId yet. That is a
        // reasonable bridge for a household and a data leak for anything else:
        // hand this method a business or neighborhood group and the
        // household-scoped queries necessarily return empty — no plan row can
        // carry a non-household group id — so the fallbacks would fire and
        // return the group owner's PERSONAL places to whoever asked.
        // MapPlaceResource filters on groupType too; this stays because the
        // fallback is the dangerous half.
        boolean isHousehold = "Household".equalsIgnoreCase(household.getGroupType());
        String ownerEmail = isHousehold ? household.getOwnerEmail() : null;

        // A PLACE THAT EXISTS IS RETURNED EVEN WHEN IT CANNOT BE DRAWN.
        // Nothing in this backend geocodes on write, so an address-only row is
        // the ordinary output of the wizard. Rows carry `mappable`; the client
        // lists what exists and pins only what it can place. Do NOT geocode
        // here (an external call inside a read path).

        // 1. Home — from the household Group itself.
        if (household.getLatitude() != null && household.getLongitude() != null) {
            out.add(place("group:" + hid, "house",
                    household.getLatitude(), household.getLongitude(),
                    nz(household.getGroupName(), "Home"),
                    household.getAddress(), "group", null, null, "home"));
        } else if (isPresent(household.getAddress())) {
            out.add(place("group:" + hid, "house", null, null,
                    nz(household.getGroupName(), "Home"),
                    household.getAddress(), "group", null, null, "home"));
        }

        // 2. The deployment, if one is live — the newest wins.
        PlanActivation live = isHousehold
                ? activationRepo.findLiveForHousehold(household, Instant.now()).stream().findFirst().orElse(null)
                : null;

        // 3. Meeting place — the deployment's selection, else the plan's primary.
        MeetingPlace meet;
        String meetRole;
        if (live != null) {
            meet = live.getMeetingPlaceId() == null ? null
                    : meetingPlaceRepo.findById(live.getMeetingPlaceId()).orElse(null);
            meetRole = "selected-meeting";
        } else {
            List<MeetingPlace> meets = meetingPlaceRepo.findByHouseholdId(hid);
            if (meets.isEmpty() && ownerEmail != null) meets = meetingPlaceRepo.findByOwnerEmail(ownerEmail);
            meet = meets.stream()
                    .filter(MapPlaceService::isRealMeetingPlace)
                    .min(Comparator.comparing((MeetingPlace m) -> !m.isDeploy())
                            .thenComparing(m -> m.getMeetingTier() == null ? Integer.MAX_VALUE : m.getMeetingTier().ordinal())
                            .thenComparing(MeetingPlace::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElse(null);
            meetRole = "meeting";
        }
        if (meet != null && isRealMeetingPlace(meet)) {
            out.add(place("meetup:" + meet.getId(), "meetup",
                    meet.getLat(), meet.getLng(),
                    nz(meet.getName(), "Meeting place"), meet.getAddress(), "meeting_place",
                    meet.getMeetingTier() == null ? null : meet.getMeetingTier().name(),
                    meet.isDeploy(), meetRole));
        }

        // 4. Shelter — same rule. THE PREDICATE IS THE SHARP EDGE:
        // EvacuationPlanService.updateRouteNotes creates a plan row with no
        // shelter fields; testing a defaulted name would sprout a phantom
        // place literally named "Shelter". Test the RAW fields.
        EvacuationPlan shelter;
        String shelterRole;
        if (live != null) {
            shelter = live.getEvacPlanId() == null ? null
                    : evacuationPlanRepo.findById(live.getEvacPlanId()).orElse(null);
            shelterRole = "selected-shelter";
        } else {
            List<EvacuationPlan> evacs = evacuationPlanRepo.findByHouseholdId(hid);
            if (evacs.isEmpty() && ownerEmail != null) evacs = evacuationPlanRepo.findByOwnerEmail(ownerEmail);
            shelter = evacs.stream()
                    .filter(MapPlaceService::isRealShelter)
                    .min(Comparator.comparing((EvacuationPlan e) -> !e.isDeploy())
                            .thenComparing(EvacuationPlan::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElse(null);
            shelterRole = "shelter";
        }
        if (shelter != null && isRealShelter(shelter)) {
            out.add(place("shelter:" + shelter.getId(), "shelter",
                    shelter.getLat(), shelter.getLng(),
                    nz(shelter.getShelterName(), "Shelter"), shelter.getShelterAddress(), "evacuation_plan",
                    null, shelter.isDeploy(), shelterRole));
        }

        // 5. Starting points — every one the plan names, household-scoped.
        List<OriginLocation> origins = originLocationRepo.findByHouseholdId(hid);
        if (origins.isEmpty() && ownerEmail != null) origins = originLocationRepo.findByOwnerEmailIgnoreCase(ownerEmail);
        origins.stream()
                .filter(o -> isPresent(o.getName()) || isPresent(o.getAddress()) || GeoUtil.validLatLng(o.getLat(), o.getLng()))
                .filter(o -> !isTheHome(o, household))
                .sorted(Comparator.comparing(OriginLocation::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(o -> out.add(place("start:" + o.getId(), "start",
                        o.getLat(), o.getLng(),
                        nz(o.getName(), "Starting point"), o.getAddress(), "origin_location",
                        null, null, "start")));

        return out;
    }

    private static boolean isRealMeetingPlace(MeetingPlace m) {
        return isPresent(m.getName()) || isPresent(m.getAddress()) || (m.getLat() != null && m.getLng() != null);
    }

    private static boolean isRealShelter(EvacuationPlan e) {
        return (e.getLat() != null && e.getLng() != null)
                || isPresent(e.getShelterName()) || isPresent(e.getShelterAddress());
    }

    /** A starting point at the household's own home is the home — drawn once. */
    private static boolean isTheHome(OriginLocation o, Group household) {
        if (GeoUtil.validLatLng(o.getLat(), o.getLng())
                && GeoUtil.validLatLng(household.getLatitude(), household.getLongitude())) {
            return GeoUtil.haversineKm(o.getLat(), o.getLng(),
                    household.getLatitude(), household.getLongitude()) < SAME_PLACE_KM;
        }
        return isPresent(o.getAddress()) && isPresent(household.getAddress())
                && o.getAddress().trim().equalsIgnoreCase(household.getAddress().trim());
    }

    /** Builds a place row, deriving `mappable` from the coordinates it was given. */
    private static MapPlaceDto place(String id, String kind, Double lat, Double lng,
                                     String name, String address, String source,
                                     String tier, Boolean deploy, String role) {
        return new MapPlaceDto(id, kind, lat, lng, name, address, source,
                GeoUtil.validLatLng(lat, lng), tier, deploy, role);
    }

    private static boolean isPresent(String v) {
        return v != null && !v.isBlank();
    }

    private static String nz(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
