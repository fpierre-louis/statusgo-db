-- Stable read order for list-shaped @ElementCollection — audit P2-4
--
-- Adds an `ord` integer column to each side-table backing a List<X>
-- @ElementCollection so Hibernate can populate @OrderColumn on read.
-- Without this column, Postgres returns side-table rows in whatever
-- physical order the heap happens to hold, which leaks into the API
-- (member lists shuffling on every fetch, image carousels reordering
-- between callers, etc.) — race audit DB-05.
--
-- Set<X> + Map<K,V> @ElementCollection tables are deliberately omitted:
-- Set has no defined order, Map is keyed. Only List<X> needs @OrderColumn.
--
-- Existing rows retain ord = NULL; Hibernate treats nulls as 0 and the
-- next write rewrites the side-table with a fully-numbered ord sequence
-- (delete-all-then-insert is how @ElementCollection always behaves on
-- update). No backfill required for beta.

-- Group: List<String> collections
ALTER TABLE group_admin_emails           ADD COLUMN IF NOT EXISTS ord INTEGER;
ALTER TABLE group_member_emails          ADD COLUMN IF NOT EXISTS ord INTEGER;
ALTER TABLE group_pending_member_emails  ADD COLUMN IF NOT EXISTS ord INTEGER;
ALTER TABLE group_sub_group_ids          ADD COLUMN IF NOT EXISTS ord INTEGER;
ALTER TABLE group_parent_group_ids       ADD COLUMN IF NOT EXISTS ord INTEGER;

-- Post (community Task): List<String> imageKeys
ALTER TABLE task_image_keys              ADD COLUMN IF NOT EXISTS ord INTEGER;

-- GroupPost: List<String> tags + mentions (Hibernate-default table names)
ALTER TABLE group_post_tags              ADD COLUMN IF NOT EXISTS ord INTEGER;
ALTER TABLE group_post_mentions          ADD COLUMN IF NOT EXISTS ord INTEGER;

-- AskTip: List<String> imageKeys (cover + inline images, display order)
ALTER TABLE ask_tip_image_keys           ADD COLUMN IF NOT EXISTS ord INTEGER;

-- Demographic: List<String> adminEmails
ALTER TABLE demographic_admin_emails     ADD COLUMN IF NOT EXISTS ord INTEGER;
