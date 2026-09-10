-- RC-3 Phase 2 · Emergency Need Profile (Level 1) + prepared Support Plan (Level 2).
--
-- A preparedness profile, NOT a medical record. Every column had to answer:
-- would knowing this materially change how this person prepares, evacuates,
-- shelters, communicates, reunifies or receives emergency assistance? Diagnoses,
-- medication schedules, allergies, physicians and appointments all fail that
-- test and are permanently out of scope.
--
-- No denormalized needs-support flag on user_info or household_manual_member:
-- the profile row IS the flag, and a copy on two entities would be two more
-- migrations plus a value that can drift from the row it describes.
CREATE TABLE IF NOT EXISTS emergency_support_profile (
    id                          BIGSERIAL PRIMARY KEY,
    household_id                VARCHAR(64)  NOT NULL,
    subject_type                VARCHAR(16)  NOT NULL,   -- 'user' | 'manual'
    subject_id                  VARCHAR(255) NOT NULL,

    needs_evacuation_assistance BOOLEAN      NOT NULL DEFAULT FALSE,
    cannot_use_stairs           BOOLEAN      NOT NULL DEFAULT FALSE,
    mobility_note               VARCHAR(120),

    preferred_language          VARCHAR(40),

    power_dependent_equipment   BOOLEAN      NOT NULL DEFAULT FALSE,
    equipment_note              VARCHAR(120),
    refrigerated_medication     BOOLEAN      NOT NULL DEFAULT FALSE,
    critical_medication         BOOLEAN      NOT NULL DEFAULT FALSE,

    accessible_transport_needed BOOLEAN      NOT NULL DEFAULT FALSE,

    service_animal              BOOLEAN      NOT NULL DEFAULT FALSE,
    service_animal_note         VARCHAR(120),

    support_note                VARCHAR(240),

    updated_at                  TIMESTAMPTZ  NOT NULL,
    updated_by_email            VARCHAR(255)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_support_profile_subject
    ON emergency_support_profile (household_id, subject_type, subject_id);

-- The read every household surface makes.
CREATE INDEX IF NOT EXISTS idx_support_profile_household
    ON emergency_support_profile (household_id);

-- Operational communication needs, as a set. Values are what a helper must DO
-- differently -- TEXT_NOT_VOICE, VISUAL_ALERTS, LARGE_TEXT_OR_AUDIO,
-- PLAIN_LANGUAGE, INTERPRETER_OR_LANGUAGE -- so deaf, low-vision,
-- limited-English and cognitive-support needs are all distinguishable without
-- SitPrep ever recording why.
CREATE TABLE IF NOT EXISTS emergency_support_communication_need (
    profile_id BIGINT      NOT NULL REFERENCES emergency_support_profile(id) ON DELETE CASCADE,
    need       VARCHAR(40) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_support_communication_need_profile
    ON emergency_support_communication_need (profile_id);

-- PLAN STATE ONLY. No accepted_at, no availability, deliberately: adding either
-- would let a surface imply coverage nobody confirmed. Runtime acceptance is
-- Level 3, scoped to a plan activation, and is not shipped.
CREATE TABLE IF NOT EXISTS emergency_support_assignment (
    id                BIGSERIAL PRIMARY KEY,
    household_id      VARCHAR(64)  NOT NULL,
    subject_type      VARCHAR(16)  NOT NULL,
    subject_id        VARCHAR(255) NOT NULL,
    role              VARCHAR(16)  NOT NULL,   -- 'PRIMARY' | 'BACKUP'
    helper_type       VARCHAR(16)  NOT NULL,   -- 'MEMBER'  | 'CONTACT'
    helper_user_email VARCHAR(255),
    helper_contact_id BIGINT,
    helper_name       VARCHAR(160),
    helper_note       VARCHAR(160),
    assigned_at       TIMESTAMPTZ  NOT NULL,
    assigned_by_email VARCHAR(255)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_support_assignment_subject_role
    ON emergency_support_assignment (household_id, subject_type, subject_id, role);

CREATE INDEX IF NOT EXISTS idx_support_assignment_household
    ON emergency_support_assignment (household_id);
