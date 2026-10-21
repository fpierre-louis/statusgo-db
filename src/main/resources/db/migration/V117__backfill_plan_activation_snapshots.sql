-- Plan data consistency closeout (2026-10-21).
-- V116 gave a live plan its own copy of its places, but rows started before it
-- had none and kept reading the saved row, so an edit could still move an
-- ACTIVE pre-V116 plan silently. This takes their snapshot now, from the saved
-- row as it is today, so every plan (live or ended) reads its own copy and the
-- saved-row fallback can go. A row whose saved place is already gone stays
-- null: there is nothing to copy, and the fallback would have found nothing too.
--
-- The JSON keys are the record components of MeetingPlaceSnapshotDto and
-- EvacuationPlanSnapshotDto (PlanActivationDtos), which Hibernate reads back.

UPDATE plan_activations a
SET meeting_snapshot = jsonb_build_object(
        'id', m.id,
        'name', m.name,
        'location', m.location,
        'address', m.address,
        'phoneNumber', m.phone_number,
        'additionalInfo', m.additional_info,
        'lat', m.lat,
        'lng', m.lng)
FROM meeting_place m
WHERE a.meeting_place_id = m.id
  AND a.meeting_snapshot IS NULL;

UPDATE plan_activations a
SET evac_snapshot = jsonb_build_object(
        'id', e.id,
        'name', e.name,
        'origin', e.origin,
        'destination', e.destination,
        'shelterName', e.shelter_name,
        'shelterAddress', e.shelter_address,
        'shelterPhoneNumber', e.shelter_phone_number,
        'lat', e.lat,
        'lng', e.lng,
        'travelMode', e.travel_mode,
        'shelterInfo', e.shelter_info)
FROM evacuation_plan e
WHERE a.evac_plan_id = e.id
  AND a.evac_snapshot IS NULL;
