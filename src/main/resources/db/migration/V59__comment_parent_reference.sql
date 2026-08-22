-- Comment threading — a real parent reference on BOTH comment families.
--
-- Replies have been a CONTENT PREFIX since Phase 2:
--   "> Replying to {name}:\n> {snippet}\n\n{body}"
-- parsed by the FE. Zero schema cost, and the bill has come due. That shape
-- cannot nest, cannot be permalinked, cannot be collapsed, and duplicates text
-- that goes stale the moment the parent is edited.
--
-- It also COMPOUNDS, which is visible in prod today. task_comment rows 67 and
-- 68 read:
--     "> Replying to X: > > Replying to X: > > Replying to Y: > Hi  Hi  Oh"
-- Each reply re-quotes the entire quoted chain of the one above it, so the
-- prefix grows with depth while the actual message stays one word.
--
-- BOTH TABLES IN LOCKSTEP, deliberately. `comment` (GroupPostComment, group
-- chat) and `task_comment` (PostComment, community feed) are kept
-- column-for-column identical because the eventual GroupPost/Post merge is a
-- mechanical migration only while that holds. Adding this to one would be the
-- first divergence.
--
-- NAME COLLISION CHECKED against the LIVE schema, not the migration history:
-- `\d comment` and `\d task_comment` carry no parent_comment_id. `parent_task_id`
-- belongs to `task` (Post.parentPostId — the repost/quote pointer, an entirely
-- different feature) and `parent_group_id` to `group_parent_group_ids`.
--
-- ON DELETE SET NULL — ORPHAN, NOT CASCADE, NOT TOMBSTONE.
--   • Cascade is wrong because a comment's replies are not its author's
--     property. A moderator removing one comment would silently delete a
--     stranger's answer underneath it.
--   • A tombstone ("[deleted]" placeholder retaining the row) needs a deleted
--     state on the entity and a rendering contract on both clients. Neither
--     exists, and inventing them here would ship a state nothing renders.
--   • SET NULL promotes an orphaned reply to a top-level comment. Lossy about
--     structure, honest about content, and destroys nothing.
--
-- DEPTH IS CAPPED AT ONE (root + replies) IN THE SERVICE, NOT HERE. A schema
-- CHECK cannot see the grandparent without a recursive query on every insert.
-- PostCommentService/GroupPostCommentService re-point a reply-to-a-reply at the
-- root instead of rejecting it, so no client can create a third level. The
-- column stays a plain nullable FK so raising the cap later is a service change.
--
-- ROW COUNTS MEASURED BEFORE APPLYING: comment = 119 rows (2 prefix-quoted),
-- task_comment = 7 rows (6 prefix-quoted). Both columns land NULL; nothing is
-- backfilled. See the note at the bottom for why.

ALTER TABLE comment      ADD COLUMN IF NOT EXISTS parent_comment_id BIGINT;
ALTER TABLE task_comment ADD COLUMN IF NOT EXISTS parent_comment_id BIGINT;

-- Self-referencing FKs. Named explicitly so a later merge can drop them by name.
ALTER TABLE comment
    DROP CONSTRAINT IF EXISTS fk_comment_parent_comment;
ALTER TABLE comment
    ADD CONSTRAINT fk_comment_parent_comment
    FOREIGN KEY (parent_comment_id) REFERENCES comment (id) ON DELETE SET NULL;

ALTER TABLE task_comment
    DROP CONSTRAINT IF EXISTS fk_task_comment_parent_comment;
ALTER TABLE task_comment
    ADD CONSTRAINT fk_task_comment_parent_comment
    FOREIGN KEY (parent_comment_id) REFERENCES task_comment (id) ON DELETE SET NULL;

-- "Give me the replies to this comment" is the read this exists to serve.
-- Plain transactional CREATE INDEX, not CONCURRENTLY: these tables are 119 and
-- 7 rows, and CONCURRENTLY has timed out against this RDS instance before and
-- left an INVALID index behind (see SYSTEM_TRAPS_AND_PATTERNS).
CREATE INDEX IF NOT EXISTS idx_comment_parent_comment_id
    ON comment (parent_comment_id);
CREATE INDEX IF NOT EXISTS idx_task_comment_parent_comment_id
    ON task_comment (parent_comment_id);

-- ── Drift repair, same migration because it is the same defect ────────────
-- GroupPostComment declares `columnDefinition = "text"` + LONGVARCHAR, but the
-- live column is character varying(255). The entity and the database have
-- disagreed since the baseline. That was survivable while comments were short;
-- it is not survivable alongside a prefix that compounds with depth, and the
-- deepest prod row is already ~90 characters of pure quote. A fourth-level
-- group reply would throw on insert.
--
-- varchar(255) -> text is a widening: no data loss, no rewrite in Postgres.
ALTER TABLE comment ALTER COLUMN content TYPE text;

-- ── The 8 existing prefix-quoted replies are NOT migrated ─────────────────
-- They cannot be, reliably. The prefix carries an author DISPLAY NAME and a
-- text snippet, never an id, so resolving a parent means matching a name
-- against the other comments on the same post — ambiguous the moment two
-- people share a first name, which "Dione" / "Dione Pierre-Louis" /
-- 'Francis "Dione"' already are on task 62. Rows 67 and 68 quote a quote, so
-- even the INTENDED parent is a guess.
--
-- Guessing here writes a wrong edge into a brand-new source of truth to save 8
-- rows, 6 of which are test data on one post. They keep rendering through the
-- FE's existing prefix parser, which is untouched and still correct for them.
