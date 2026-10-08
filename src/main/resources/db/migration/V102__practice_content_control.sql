-- V102 — Practice content kill switch (scenarios & Family Practice epic,
-- EXEC-A content safety foundation, 2026-10-08).
--
-- Practice content (choose-your-path scenarios, family activities) ships as
-- versioned JSON in the jar; its publish state and safety review live in the
-- file. This table is the one thing an operator can change WITHOUT a deploy:
-- a row with disabled_at set removes every version of content_key from the
-- startable catalog and stops in-progress runs on it.
--
-- One row per key, kept on re-enable (disabled_at cleared) so the last reason
-- and actor stay visible. No FK: a key may be disabled before or after its
-- content ships.
--
-- Plain transactional DDL (no CONCURRENTLY — prod RDS times out).

CREATE TABLE IF NOT EXISTS practice_content_control (
    content_key     VARCHAR(96)  PRIMARY KEY,
    disabled_at     TIMESTAMPTZ  NULL,
    disabled_reason VARCHAR(500) NULL,
    updated_by      VARCHAR(320) NULL,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
