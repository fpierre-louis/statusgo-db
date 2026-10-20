-- Evacuation plan clarity (2026-10-20): "Meet, then leave".
-- route_plan: MEET_THEN_GO when the household meets first and then goes to the
-- destination together; null for the single-step choices (stay, meet, leave).
-- stage: MEETING until an admin taps "We've met, now go", then GOING.
ALTER TABLE plan_activations ADD COLUMN IF NOT EXISTS route_plan VARCHAR(32);
ALTER TABLE plan_activations ADD COLUMN IF NOT EXISTS stage VARCHAR(16);
