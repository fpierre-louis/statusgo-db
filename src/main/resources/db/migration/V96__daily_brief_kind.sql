-- V96 — chk_task_kind gains 'daily-brief' (EXEC-3C, 2026-10-03).
--
-- SitPrep's daily brief is ONE system-authored post (PostKind.DAILY_BRIEF)
-- whose content is built per viewer on read. A new PostKind wire value
-- REQUIRES this CHECK in a migration (trap T-4; V87 did the same for
-- 'hazard'): the first deploy without it failed the insert with
-- chk_task_kind — softly, the service logs and retries next start. H2 cannot
-- see the constraint, which is why tests didn't catch it.
--
-- The list is the live prod definition (read 2026-10-03) plus 'daily-brief',
-- nothing removed, so no existing row can violate it. Plain transactional DDL.

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
            'hazard',
            'daily-brief'
        )
    );
