-- V64 — @-mentions become a structured reference instead of display text.
--
-- WHAT CHANGES. Mentions were stored as plain text ("@Ana ") and re-derived on
-- read by scanning content for any @label matching a roster FIRST NAME. That
-- put the reference in the display name, so a rename unmade the mention and two
-- members called Chris both got notified. The reference now lives in a token
-- inside content -- @[uid:<uuid>] -- and these tables carry the same ids
-- denormalized so the notify path never has to parse.
--
-- ROW COUNTS THIS TOUCHES, measured on prod immediately before applying:
--   group_post_mentions           0 rows   (feature never used in production)
--   task_comment                  9 rows   0 containing '@'
--   comment                     119 rows   0 containing '@'
-- So there is NO backfill and no back-compat format to keep alive. This is a
-- replace, not a migration, which is only true because it is being done now.
--
-- ON DELETE CASCADE IS LOAD-BEARING, NOT TIDINESS. AccountDeletionService
-- removes a departing user's content with JPQL bulk DELETE:
--     DELETE FROM GroupPostComment c WHERE LOWER(c.author) = :e
-- A JPQL bulk delete bypasses Hibernate's cascade handling entirely -- it emits
-- one SQL DELETE and never loads the entities -- so it does NOT clear an
-- @ElementCollection. Without a database-level cascade the first account
-- deletion after this migration would fail on a foreign-key violation, on a
-- path that already has a 409 branch and looks well covered. The constraint is
-- what makes the existing deletion code keep working unchanged.

CREATE TABLE task_comment_mentions (
    task_comment_id   BIGINT       NOT NULL REFERENCES task_comment(id) ON DELETE CASCADE,
    mentioned_user_id VARCHAR(36)  NOT NULL,
    ord               INTEGER      NOT NULL,
    PRIMARY KEY (task_comment_id, ord)
);

CREATE TABLE comment_mentions (
    comment_id        BIGINT       NOT NULL REFERENCES comment(id) ON DELETE CASCADE,
    mentioned_user_id VARCHAR(36)  NOT NULL,
    ord               INTEGER      NOT NULL,
    PRIMARY KEY (comment_id, ord)
);

-- "Threads where I was mentioned" is the query these exist to make cheap.
-- Plain transactional CREATE INDEX, not CONCURRENTLY: both tables are empty, so
-- the lock is instantaneous, and CONCURRENTLY has timed out against this RDS
-- instance before and left an INVALID index behind. It also cannot run inside a
-- transaction, which would make this file impossible to dry-run.
CREATE INDEX idx_task_comment_mentions_user ON task_comment_mentions (mentioned_user_id);
CREATE INDEX idx_comment_mentions_user      ON comment_mentions      (mentioned_user_id);

-- group_post_mentions held EMAILS. It now holds user ids, like the two tables
-- above. Renaming the column rather than leaving it called "mentions" is the
-- point: a silent change of meaning under an unchanged name is precisely how
-- the next reader gets it wrong, and at 0 rows the rename is free.
ALTER TABLE group_post_mentions RENAME COLUMN mentions TO mentioned_user_id;

COMMENT ON COLUMN task_comment_mentions.mentioned_user_id IS
    'user_info.user_id of a mentioned account. Denormalized from the @[uid:...] tokens in task_comment.content so the notify path does not parse content. Content is the source of truth; this is the index.';
COMMENT ON COLUMN comment_mentions.mentioned_user_id IS
    'user_info.user_id of a mentioned account. Mirrors task_comment_mentions column-for-column so the eventual Post/GroupPost merge stays mechanical.';
COMMENT ON COLUMN group_post_mentions.mentioned_user_id IS
    'user_info.user_id of a mentioned account. Held EMAILS before V64.';
