-- Backfill ord values for list-shaped @ElementCollection side tables.
--
-- V5 added `ord INTEGER` columns to support @OrderColumn but left existing
-- rows with NULL. Hibernate's ListEntityPersister treats NULL ord as 0,
-- which collapses every pre-V5 row onto position 0 and corrupts the
-- in-memory list (last NULL row wins, others are lost on hydration).
-- V5's comment said "no backfill required for beta" — under launch
-- posture with real user data, that's no longer acceptable.
--
-- Backfill strategy: deterministic ROW_NUMBER() per parent FK ordered by
-- Postgres ctid (physical row pointer — stable for the lifetime of the
-- row, monotonically assigned at INSERT). Subtract 1 so the sequence is
-- 0-based to match Hibernate @OrderColumn's default base.
--
-- After backfill, lock the column to NOT NULL so direct-SQL inserts can't
-- reintroduce NULL ord. Hibernate's delete-all-then-insert on update will
-- always write 0..(n-1), so NOT NULL is invisible to the @Entity layer.

-- =====================================================================
-- Group: 5 List<String> side tables
-- =====================================================================

UPDATE group_admin_emails SET ord = sub.rn - 1
FROM (
    SELECT ctid, ROW_NUMBER() OVER (PARTITION BY group_id ORDER BY ctid) AS rn
    FROM group_admin_emails WHERE ord IS NULL
) sub
WHERE group_admin_emails.ctid = sub.ctid;

UPDATE group_member_emails SET ord = sub.rn - 1
FROM (
    SELECT ctid, ROW_NUMBER() OVER (PARTITION BY group_id ORDER BY ctid) AS rn
    FROM group_member_emails WHERE ord IS NULL
) sub
WHERE group_member_emails.ctid = sub.ctid;

UPDATE group_pending_member_emails SET ord = sub.rn - 1
FROM (
    SELECT ctid, ROW_NUMBER() OVER (PARTITION BY group_id ORDER BY ctid) AS rn
    FROM group_pending_member_emails WHERE ord IS NULL
) sub
WHERE group_pending_member_emails.ctid = sub.ctid;

UPDATE group_sub_group_ids SET ord = sub.rn - 1
FROM (
    SELECT ctid, ROW_NUMBER() OVER (PARTITION BY group_id ORDER BY ctid) AS rn
    FROM group_sub_group_ids WHERE ord IS NULL
) sub
WHERE group_sub_group_ids.ctid = sub.ctid;

UPDATE group_parent_group_ids SET ord = sub.rn - 1
FROM (
    SELECT ctid, ROW_NUMBER() OVER (PARTITION BY group_id ORDER BY ctid) AS rn
    FROM group_parent_group_ids WHERE ord IS NULL
) sub
WHERE group_parent_group_ids.ctid = sub.ctid;

-- =====================================================================
-- Post (community Task table): imageKeys
-- =====================================================================

UPDATE task_image_keys SET ord = sub.rn - 1
FROM (
    SELECT ctid, ROW_NUMBER() OVER (PARTITION BY task_id ORDER BY ctid) AS rn
    FROM task_image_keys WHERE ord IS NULL
) sub
WHERE task_image_keys.ctid = sub.ctid;

-- =====================================================================
-- GroupPost: tags + mentions
-- =====================================================================

UPDATE group_post_tags SET ord = sub.rn - 1
FROM (
    SELECT ctid, ROW_NUMBER() OVER (PARTITION BY group_post_id ORDER BY ctid) AS rn
    FROM group_post_tags WHERE ord IS NULL
) sub
WHERE group_post_tags.ctid = sub.ctid;

UPDATE group_post_mentions SET ord = sub.rn - 1
FROM (
    SELECT ctid, ROW_NUMBER() OVER (PARTITION BY group_post_id ORDER BY ctid) AS rn
    FROM group_post_mentions WHERE ord IS NULL
) sub
WHERE group_post_mentions.ctid = sub.ctid;

-- =====================================================================
-- AskTip: imageKeys
-- =====================================================================

UPDATE ask_tip_image_keys SET ord = sub.rn - 1
FROM (
    SELECT ctid, ROW_NUMBER() OVER (PARTITION BY tip_id ORDER BY ctid) AS rn
    FROM ask_tip_image_keys WHERE ord IS NULL
) sub
WHERE ask_tip_image_keys.ctid = sub.ctid;

-- =====================================================================
-- Demographic: adminEmails
-- =====================================================================

UPDATE demographic_admin_emails SET ord = sub.rn - 1
FROM (
    SELECT ctid, ROW_NUMBER() OVER (PARTITION BY demographic_id ORDER BY ctid) AS rn
    FROM demographic_admin_emails WHERE ord IS NULL
) sub
WHERE demographic_admin_emails.ctid = sub.ctid;

-- =====================================================================
-- Lock ord NOT NULL to prevent future direct-SQL inserts from re-introducing
-- the same problem. Hibernate always writes ord on @ElementCollection update
-- (delete-all-then-insert pattern), so this is invisible at the entity layer.
-- =====================================================================

ALTER TABLE group_admin_emails           ALTER COLUMN ord SET NOT NULL;
ALTER TABLE group_member_emails          ALTER COLUMN ord SET NOT NULL;
ALTER TABLE group_pending_member_emails  ALTER COLUMN ord SET NOT NULL;
ALTER TABLE group_sub_group_ids          ALTER COLUMN ord SET NOT NULL;
ALTER TABLE group_parent_group_ids       ALTER COLUMN ord SET NOT NULL;
ALTER TABLE task_image_keys              ALTER COLUMN ord SET NOT NULL;
ALTER TABLE group_post_tags              ALTER COLUMN ord SET NOT NULL;
ALTER TABLE group_post_mentions          ALTER COLUMN ord SET NOT NULL;
ALTER TABLE ask_tip_image_keys           ALTER COLUMN ord SET NOT NULL;
ALTER TABLE demographic_admin_emails     ALTER COLUMN ord SET NOT NULL;
