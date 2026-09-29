-- V88 — @-mentions in community POSTS (Composer V2 C9f, owner Q6, 2026-09-28).
--
-- Mirrors V64's task_comment_mentions column-for-column: the reference lives
-- in the post description as an @[uid:<uuid>] token; this table carries the
-- same ids denormalized so the notify path and "posts where I was mentioned"
-- never parse prose. Content is the source of truth; this is the index.
--
-- NO BACKFILL. Until this migration the composer's @ control was disabled
-- (a control with no endpoint), so no post description holds a token. Rows
-- start empty and are written by PostService.create / patch.
--
-- ON DELETE CASCADE IS LOAD-BEARING (the V64 reasoning): AccountDeletionService
-- removes a departing user's posts with a JPQL bulk DELETE, which bypasses
-- Hibernate's @ElementCollection cleanup. Without the database cascade the
-- first account deletion after this migration would fail on a foreign key.
--
-- Plain transactional CREATE INDEX, not CONCURRENTLY: the table is empty, and
-- CONCURRENTLY has timed out against this RDS instance before.

CREATE TABLE task_mentions (
    task_id           BIGINT       NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    mentioned_user_id VARCHAR(36)  NOT NULL,
    ord               INTEGER      NOT NULL,
    PRIMARY KEY (task_id, ord)
);

CREATE INDEX idx_task_mentions_user ON task_mentions (mentioned_user_id);

COMMENT ON COLUMN task_mentions.mentioned_user_id IS
    'user_info.user_id of a mentioned account. Denormalized from the @[uid:...] tokens in task.description so the notify path does not parse content.';
