-- V67 - Weekly preparedness drill notification preference.
--
-- The dashboard challenge dispatcher is separate from the opt-in weekly
-- check-in ritual. Default opt-in preserves existing behavior until a user
-- chooses to mute drill nudges.

ALTER TABLE user_alert_preference
    ADD COLUMN IF NOT EXISTS drills boolean NOT NULL DEFAULT true;
