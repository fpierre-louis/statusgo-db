-- Community redesign schema backfill (built 2026-06, migration 2026-06-15).
--
-- The Community feed overhaul added JPA entities/columns WITHOUT a matching
-- Flyway migration, so on the next boot `ddl-auto: validate` aborted with
-- "Schema-validation: missing table [post_confirm]" (and would have failed
-- next on the additive `task` columns). This reconciles the DB to the
-- entities as they exist in code:
--   • PostConfirm.java  -> table `post_confirm` (mirrors `task_reaction`)
--   • Post.java         -> 14 additive nullable columns on `task`
--                          (official / civic-report / news / pin metadata)
--                          + the idx_task_tagged_agency index
--   • PostKind.java      adds 3 wire values -> widen the V9 chk_task_kind
--                          CHECK so official/civic-report/news rows insert.
--
-- Every statement is idempotent (IF NOT EXISTS / DROP ... IF EXISTS) so it
-- is safe no matter what partial state any environment's schema is in.

-- 1. First-class "Confirm" signal on a community post. Mirrors task_reaction
--    (BIGSERIAL id, BIGINT task_id, TIMESTAMP) so the count/viewer folding in
--    PostConfirmService matches PostReactionService. One row per (task, user).
CREATE TABLE IF NOT EXISTS post_confirm (
    id         BIGSERIAL PRIMARY KEY,
    task_id    BIGINT NOT NULL,
    user_email VARCHAR(320) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_post_confirm_task_user UNIQUE (task_id, user_email)
);
CREATE INDEX IF NOT EXISTS idx_post_confirm_task ON post_confirm (task_id);
CREATE INDEX IF NOT EXISTS idx_post_confirm_user ON post_confirm (user_email);

-- 2. Additive per-feed-item-type columns on `task` (Post.java). All nullable;
--    meaningful only on the matching feed-item type, derived FE-side.
ALTER TABLE task ADD COLUMN IF NOT EXISTS official_tier          VARCHAR(16);
ALTER TABLE task ADD COLUMN IF NOT EXISTS pinned_at              TIMESTAMP;
ALTER TABLE task ADD COLUMN IF NOT EXISTS pinned_by              VARCHAR(128);
ALTER TABLE task ADD COLUMN IF NOT EXISTS pinned_until           TIMESTAMP;
ALTER TABLE task ADD COLUMN IF NOT EXISTS civic_status           VARCHAR(16);
ALTER TABLE task ADD COLUMN IF NOT EXISTS civic_category         VARCHAR(16);
ALTER TABLE task ADD COLUMN IF NOT EXISTS tagged_agency_group_id VARCHAR(64);
ALTER TABLE task ADD COLUMN IF NOT EXISTS agency_note            VARCHAR(280);
ALTER TABLE task ADD COLUMN IF NOT EXISTS civic_acked_at         TIMESTAMP;
ALTER TABLE task ADD COLUMN IF NOT EXISTS scheduled_for          TIMESTAMP;
ALTER TABLE task ADD COLUMN IF NOT EXISTS resolved_at            TIMESTAMP;
ALTER TABLE task ADD COLUMN IF NOT EXISTS source_name            VARCHAR(120);
ALTER TABLE task ADD COLUMN IF NOT EXISTS source_url             VARCHAR(512);
ALTER TABLE task ADD COLUMN IF NOT EXISTS read_minutes           INTEGER;

-- 3. Index backing agency civic-status lookups (Post.java @Table index).
CREATE INDEX IF NOT EXISTS idx_task_tagged_agency
    ON task (tagged_agency_group_id, civic_status);

-- 4. Widen the kind CHECK (V9) to admit the new PostKind wire values, or
--    official/civic-report/news inserts violate chk_task_kind at write time.
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
            'news'
        )
    );
