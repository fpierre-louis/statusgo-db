-- V86 — follow a community thread. Community Thread C epic, unit B2
-- (Status Now/docs/epics/community_thread/EXEC-B2-thread-context.md).
--
-- Why: the thread header's Follow bell ("notify me about replies") had no
-- backing at all — Follow/FollowService are person→person. A bell that
-- toggled a flag nothing read would be a fake feature, so this table is read
-- by PostCommentService: followers of a post get a push when someone replies.
--
-- Same shape as post_confirm (task_id, user_email, created_at), one row per
-- (post, person), so following twice is a no-op by construction.
--
-- Plain transactional DDL on a new, empty table — CREATE INDEX CONCURRENTLY
-- times out on prod RDS and leaves an INVALID index (trap log). Postgres-only;
-- the H2 test profile builds the schema from the entity.

CREATE TABLE IF NOT EXISTS post_follow (
    id          BIGSERIAL    PRIMARY KEY,
    task_id     BIGINT       NOT NULL,
    user_email  VARCHAR(320) NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    CONSTRAINT uk_post_follow_task_user UNIQUE (task_id, user_email)
);

CREATE INDEX IF NOT EXISTS idx_post_follow_task ON post_follow (task_id);
CREATE INDEX IF NOT EXISTS idx_post_follow_user ON post_follow (user_email);
