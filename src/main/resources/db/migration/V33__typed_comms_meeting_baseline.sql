-- FEMA / Red Cross typed Communications + Meeting Places baseline.
-- Keeps legacy free-text role and 4-range tier_key columns for display/backfill,
-- but adds enum-backed doctrine fields used by the server readiness engine.

ALTER TABLE emergency_contacts
    ADD COLUMN IF NOT EXISTS contact_type VARCHAR(32) NOT NULL DEFAULT 'OTHER';

UPDATE emergency_contacts
   SET contact_type = CASE
       WHEN LOWER(COALESCE(role, '') || ' ' || COALESCE(address, '')) LIKE '%out-of-area%'
         OR LOWER(COALESCE(role, '') || ' ' || COALESCE(address, '')) LIKE '%out of area%'
         OR LOWER(COALESCE(role, '') || ' ' || COALESCE(address, '')) LIKE '%out-of-state%'
         OR LOWER(COALESCE(role, '') || ' ' || COALESCE(address, '')) LIKE '%out of state%'
         THEN 'OUT_OF_AREA'
       WHEN LOWER(COALESCE(role, '')) LIKE '%doctor%'
         OR LOWER(COALESCE(role, '')) LIKE '%medical%'
         OR LOWER(COALESCE(role, '')) LIKE '%physician%'
         OR LOWER(COALESCE(role, '')) LIKE '%pediatric%'
         OR LOWER(COALESCE(role, '')) LIKE '%pharmacy%'
         OR LOWER(COALESCE(role, '')) LIKE '%clinic%'
         THEN 'MEDICAL'
       WHEN LOWER(COALESCE(role, '')) LIKE '%school%'
         THEN 'SCHOOL_CAREGIVER'
       WHEN LOWER(COALESCE(role, '')) LIKE '%vet%'
         OR LOWER(COALESCE(role, '')) LIKE '%pet%'
         THEN 'PET_CAREGIVER'
       WHEN LOWER(COALESCE(role, '')) LIKE '%neighbor%'
         OR LOWER(COALESCE(role, '')) LIKE '%friend%'
         OR LOWER(COALESCE(role, '')) LIKE '%local%'
         OR LOWER(COALESCE(role, '')) LIKE '%work%'
         THEN 'LOCAL'
       ELSE contact_type
   END
 WHERE contact_type = 'OTHER';

CREATE INDEX IF NOT EXISTS idx_emergency_contacts_contact_type
    ON emergency_contacts (contact_type);

ALTER TABLE meeting_place
    ADD COLUMN IF NOT EXISTS meeting_tier VARCHAR(32) NOT NULL DEFAULT 'OTHER';

UPDATE meeting_place
   SET meeting_tier = CASE
       WHEN LOWER(COALESCE(tier_key, '')) IN ('safe_room', 'indoor_safe_room')
         THEN 'INDOOR_SAFE_ROOM'
       WHEN LOWER(COALESCE(tier_key, '')) IN ('near_home', 'neighborhood', 'outside_home')
         THEN 'OUTSIDE_HOME'
       WHEN LOWER(COALESCE(tier_key, '')) IN ('in_town', 'out_of_area', 'out_of_town')
         THEN 'OUT_OF_TOWN'
       ELSE meeting_tier
   END
 WHERE meeting_tier = 'OTHER';

CREATE INDEX IF NOT EXISTS idx_meeting_place_meeting_tier
    ON meeting_place (meeting_tier);
