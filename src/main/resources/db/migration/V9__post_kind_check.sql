-- Block malformed Post.kind values at the DB layer — audit P3-7.
--
-- Post.kind is the discriminator that decides how a community-feed row is
-- rendered on the FE. Each card component switches on this string, so an
-- unrecognized value (typo, deprecated kind left behind by a hand-edit,
-- direct INSERT from a debug script) falls through every render branch
-- and produces a blank post card. The service layer already validates
-- kind on REST writes via PostKind.isValid, but raw SQL inserts and
-- legacy data-fix scripts bypass that path. Pin the invariant to the DB.
--
-- Table name: `task` — the Post entity backs the `task` table (preserved
-- across the Phase 3b Post rename to avoid a column-shuffling migration;
-- see the @Table annotation on Post.java + the PostKind enum javadoc).
--
-- Canonical wire values come from PostKind.java — keep this list in
-- lockstep with that enum. Current set:
--   post, ask, offer, tip, recommendation, lost-found,
--   alert-update, blog-promo, marketplace, task
--
-- NULL is allowed so legacy rows that pre-date the kind discriminator
-- still validate; PostService defaults null/blank to "post" on read.

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
            'task'
        )
    );
