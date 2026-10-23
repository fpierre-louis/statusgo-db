-- V124 — Household routines (Practice redesign EXEC-R1, "Save to plan").
--
-- household_routine: one row per (household, routine). A routine is an if-then line a
-- household chose to keep ("If the smoke alarm sounds, we use the nearest safe exit and
-- meet at the mailbox."). It is PLAN DATA, like a meeting place or a contact: only an
-- explicit "Save to plan" by a household owner or admin writes it, and it is read by every
-- member through the household plan document.
--
-- The row stores the rendered if_text / then_text exactly as the household saved them (a
-- snapshot, so a renamed contact or a later template version never rewrites the household's
-- own words) plus the slot values they chose (slot_values jsonb: slotKey -> {value, refId?};
-- refId is an optional "place:<id>", "contact:<id>", "user:<id>" or "manual:<id>" pointer
-- kept only to re-open the builder on the same pick).
--
-- What the row NEVER stores: a scenario run, a Together session, a role, a turn, a child, a
-- score or any Practice history. It is not practice history, so "Clear practice history"
-- does not touch it; it goes only with the household (FK cascade) or an explicit Remove.
-- created_by_email / updated_by_email are the plan-edit audit trail (who changed the
-- household's plan), as on the other household plan tables.

CREATE TABLE IF NOT EXISTS household_routine (
    id               BIGSERIAL    PRIMARY KEY,
    household_id     VARCHAR(255) NOT NULL REFERENCES groups (group_id) ON DELETE CASCADE,
    routine_key      VARCHAR(96)  NOT NULL,
    content_version  INTEGER      NOT NULL,
    hazard_key       VARCHAR(48)  NOT NULL,
    if_text          VARCHAR(200) NOT NULL,
    then_text        VARCHAR(300) NOT NULL,
    slot_values      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by_email VARCHAR(255),
    updated_by_email VARCHAR(255),
    row_version      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_household_routine_household_key UNIQUE (household_id, routine_key)
);
