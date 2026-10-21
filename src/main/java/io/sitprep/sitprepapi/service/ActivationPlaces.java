package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.EvacuationPlan;
import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.dto.PlanActivationDtos.EvacuationPlanSnapshotDto;
import io.sitprep.sitprepapi.dto.PlanActivationDtos.MeetingPlaceSnapshotDto;
import io.sitprep.sitprepapi.repo.EvacuationPlanRepo;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;

import java.util.Objects;

/**
 * The places a LIVE plan uses (plan data consistency, 2026-10-21).
 *
 * <p>A live plan reads its meeting spot and destination from the snapshot it
 * took when it was started or last changed (V116), never from the saved row:
 * editing a saved place must not silently change where a household is being
 * sent, and deleting one must not blank it. Rows from before V116 have no
 * snapshot and fall back to the saved row, which is what they always did.</p>
 *
 * <p>Labels that are NOT the instruction (tier, Main, household) still come
 * from the saved row when it exists, so the map styles the place as before.</p>
 */
public final class ActivationPlaces {

    private ActivationPlaces() {}

    public static MeetingPlaceSnapshotDto snapshotOf(MeetingPlace m) {
        return m == null ? null : new MeetingPlaceSnapshotDto(
                m.getId(), m.getName(), m.getLocation(), m.getAddress(),
                m.getPhoneNumber(), m.getAdditionalInfo(), m.getLat(), m.getLng());
    }

    public static EvacuationPlanSnapshotDto snapshotOf(EvacuationPlan e) {
        return e == null ? null : new EvacuationPlanSnapshotDto(
                e.getId(), e.getName(), e.getOrigin(), e.getDestination(),
                e.getShelterName(), e.getShelterAddress(), e.getShelterPhoneNumber(),
                e.getLat(), e.getLng(), e.getTravelMode(), e.getShelterInfo());
    }

    /** Take (or retake) the live plan's snapshot from the saved rows it points at. */
    public static void snapshot(PlanActivation a, MeetingPlaceRepo meetings, EvacuationPlanRepo evacs) {
        a.setMeetingSnapshot(a.getMeetingPlaceId() == null ? null
                : meetings.findById(a.getMeetingPlaceId()).map(ActivationPlaces::snapshotOf).orElse(null));
        a.setEvacSnapshot(a.getEvacPlanId() == null ? null
                : evacs.findById(a.getEvacPlanId()).map(ActivationPlaces::snapshotOf).orElse(null));
    }

    /** The live plan's meeting spot as a DTO: its snapshot, else (pre-V116) the saved row. */
    public static MeetingPlaceSnapshotDto meetingDto(PlanActivation a, MeetingPlaceRepo meetings) {
        if (a == null || a.getMeetingPlaceId() == null) return null;
        if (a.getMeetingSnapshot() != null) return a.getMeetingSnapshot();
        return meetings.findById(a.getMeetingPlaceId()).map(ActivationPlaces::snapshotOf).orElse(null);
    }

    public static EvacuationPlanSnapshotDto evacDto(PlanActivation a, EvacuationPlanRepo evacs) {
        if (a == null || a.getEvacPlanId() == null) return null;
        if (a.getEvacSnapshot() != null) return a.getEvacSnapshot();
        return evacs.findById(a.getEvacPlanId()).map(ActivationPlaces::snapshotOf).orElse(null);
    }

    /** The live plan's meeting spot as an entity (for builders that take one); snapshot fields win. */
    public static MeetingPlace meeting(PlanActivation a, MeetingPlaceRepo meetings) {
        if (a == null || a.getMeetingPlaceId() == null) return null;
        MeetingPlace saved = meetings.findById(a.getMeetingPlaceId()).orElse(null);
        MeetingPlaceSnapshotDto s = a.getMeetingSnapshot();
        if (s == null) return saved;
        MeetingPlace m = new MeetingPlace();
        m.setId(s.id());
        m.setName(s.name());
        m.setLocation(s.location());
        m.setAddress(s.address());
        m.setPhoneNumber(s.phoneNumber());
        m.setAdditionalInfo(s.additionalInfo());
        m.setLat(s.lat());
        m.setLng(s.lng());
        if (saved != null) {
            m.setTierKey(saved.getTierKey());
            m.setMeetingTier(saved.getMeetingTier());
            m.setDeploy(saved.isDeploy());
            m.setOwnerEmail(saved.getOwnerEmail());
            m.setHouseholdId(saved.getHouseholdId());
        }
        return m;
    }

    public static EvacuationPlan evac(PlanActivation a, EvacuationPlanRepo evacs) {
        if (a == null || a.getEvacPlanId() == null) return null;
        EvacuationPlan saved = evacs.findById(a.getEvacPlanId()).orElse(null);
        EvacuationPlanSnapshotDto s = a.getEvacSnapshot();
        if (s == null) return saved;
        EvacuationPlan e = new EvacuationPlan();
        e.setId(s.id());
        e.setName(s.name());
        e.setOrigin(s.origin());
        e.setDestination(s.destination());
        e.setShelterName(s.shelterName());
        e.setShelterAddress(s.shelterAddress());
        e.setShelterPhoneNumber(s.shelterPhoneNumber());
        e.setLat(s.lat());
        e.setLng(s.lng());
        e.setTravelMode(s.travelMode());
        e.setShelterInfo(s.shelterInfo());
        if (saved != null) {
            e.setDeploy(saved.isDeploy());
            e.setOwnerEmail(saved.getOwnerEmail());
            e.setHouseholdId(saved.getHouseholdId());
        }
        return e;
    }

    /**
     * Has the SAVED meeting spot moved on from what the live plan uses? True
     * when the row was edited (name, address or coordinates) or deleted. False
     * with no snapshot (nothing to compare) or no meeting spot.
     */
    public static boolean meetingChanged(PlanActivation a, MeetingPlaceRepo meetings) {
        MeetingPlaceSnapshotDto s = a == null ? null : a.getMeetingSnapshot();
        if (s == null || a.getMeetingPlaceId() == null) return false;
        return meetings.findById(a.getMeetingPlaceId())
                .map(m -> !Objects.equals(m.getName(), s.name())
                        || !Objects.equals(m.getAddress(), s.address())
                        || !Objects.equals(m.getLat(), s.lat())
                        || !Objects.equals(m.getLng(), s.lng()))
                .orElse(true);
    }

    public static boolean evacChanged(PlanActivation a, EvacuationPlanRepo evacs) {
        EvacuationPlanSnapshotDto s = a == null ? null : a.getEvacSnapshot();
        if (s == null || a.getEvacPlanId() == null) return false;
        return evacs.findById(a.getEvacPlanId())
                .map(e -> !Objects.equals(e.getShelterName(), s.shelterName())
                        || !Objects.equals(e.getName(), s.name())
                        || !Objects.equals(e.getDestination(), s.destination())
                        || !Objects.equals(e.getShelterAddress(), s.shelterAddress())
                        || !Objects.equals(e.getLat(), s.lat())
                        || !Objects.equals(e.getLng(), s.lng()))
                .orElse(true);
    }
}
