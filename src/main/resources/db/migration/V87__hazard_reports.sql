-- V87 — neighbor hazard reports. Hazard reports gameplan HR1
-- (Status Now docs/epics/hazard-reports/EXEC-HR1-backend.md).
--
-- Why: residents report fire, flooding, a closed or damaged road, downed
-- lines, hazmat, a crash or debris; neighbors confirm "still there" or "gone";
-- once confirmed, SitPrep's own routing warns about (Phase 1) and then avoids
-- (Phase 2) the area. A report is a post (kind 'hazard', so it reaches the feed
-- with comments and photos); the hazard specifics and the votes live in their
-- own tables so the post table and its service stay untouched.
--
-- 1. chk_task_kind gains 'hazard'. A new PostKind wire value REQUIRES this in
--    the same migration (trap T-4 — V51/V52: the kind shipped without the
--    CHECK and every insert 500'd; H2 cannot see it). The list below is V52's
--    plus 'hazard', nothing removed, so no existing row can violate it.
-- 2. hazard_report — one row per hazard post, keyed by the post id.
-- 3. hazard_vote — one row per (report, person); the latest vote wins.
--
-- Additive only. Plain transactional DDL (no CONCURRENTLY — times out on
-- prod; see SYSTEM_TRAPS). New, empty tables. Postgres-only; the H2 test
-- profile builds its schema from the entities.

ALTER TABLE task DROP CONSTRAINT IF EXISTS chk_task_kind;
ALTER TABLE task
    ADD CONSTRAINT chk_task_kind
    CHECK (
        kind IS NULL
        OR kind IN (
            'post',
            'ask',
            'offer',
            'tip',
            'recommendation',
            'lost-found',
            'alert-update',
            'blog-promo',
            'marketplace',
            'task',
            'official',
            'civic-report',
            'news',
            'project',
            'hazard'
        )
    );

CREATE TABLE IF NOT EXISTS hazard_report (
    task_id      BIGINT       PRIMARY KEY,
    category     VARCHAR(24)  NOT NULL,
    radius_m     INTEGER      NOT NULL,
    reported_at  TIMESTAMP    NOT NULL,
    expires_at   TIMESTAMP    NOT NULL,
    has_photo    BOOLEAN      NOT NULL DEFAULT FALSE,
    official_at  TIMESTAMP,
    official_by  VARCHAR(160),
    cleared_at   TIMESTAMP,
    cleared_by   VARCHAR(160),
    CONSTRAINT ck_hazard_report_category CHECK (category IN
        ('fire', 'flood', 'road_closed', 'road_damage', 'power_lines', 'gas_hazmat', 'crash', 'debris')),
    CONSTRAINT ck_hazard_report_radius CHECK (radius_m BETWEEN 10 AND 5000)
);

-- The read path: active reports (not cleared, not yet expired).
CREATE INDEX IF NOT EXISTS idx_hazard_report_expires ON hazard_report (expires_at);

CREATE TABLE IF NOT EXISTS hazard_vote (
    id          BIGSERIAL    PRIMARY KEY,
    task_id     BIGINT       NOT NULL,
    user_email  VARCHAR(320) NOT NULL,
    vote        VARCHAR(8)   NOT NULL,
    voted_at    TIMESTAMP    NOT NULL,
    CONSTRAINT uk_hazard_vote_task_user UNIQUE (task_id, user_email),
    CONSTRAINT ck_hazard_vote_vote CHECK (vote IN ('still', 'gone'))
);

CREATE INDEX IF NOT EXISTS idx_hazard_vote_task_time ON hazard_vote (task_id, voted_at);
