-- Household Standing Conditions — temporary but durable household operating state.
--
-- The scenario matrix kept finding the same missing primitive: "do not drink the
-- tap water", "power off 2-6 PM", "primary vehicle unavailable", "the access
-- road is still closed". These are not alerts (nobody official issued them),
-- not tasks (there is nothing to complete), not welfare status (they are about
-- the household, not a person), and not plan fields (they are temporary).
--
-- The load-bearing property is in `cleared_at`: a condition ends when a PERSON
-- ends it. An official alert expiring must never clear one, because the
-- household consequence routinely outlives the feed entry that caused it.

CREATE TABLE household_standing_condition (
    id                BIGSERIAL PRIMARY KEY,
    household_id      VARCHAR(64)  NOT NULL,

    -- Organisation and iconography only. Never a source of safety instruction:
    -- the household's own words in `instruction` are the instruction.
    category          VARCHAR(32)  NOT NULL,

    title             VARCHAR(120) NOT NULL,
    instruction       VARCHAR(500),

    -- ACTIVE | CLEARED. Clearing is a lifecycle transition, not a delete, so
    -- "who said this was over, and when" stays answerable afterwards.
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',

    created_at        TIMESTAMPTZ  NOT NULL,
    created_by_email  VARCHAR(255),
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by_email  VARCHAR(255),
    cleared_at        TIMESTAMPTZ,
    cleared_by_email  VARCHAR(255)
);

-- The only hot query: active conditions for one household, newest first.
CREATE INDEX idx_standing_condition_household_status
    ON household_standing_condition (household_id, status);
