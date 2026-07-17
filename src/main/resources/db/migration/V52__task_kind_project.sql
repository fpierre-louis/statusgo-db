-- V52: admit the 'project' PostKind wire value into the kind CHECK.
--
-- V51 added PostKind.PROJECT (bundles/projects) but did NOT widen chk_task_kind
-- (the CHECK from V9, last widened in V11). Without this, INSERTing a
-- kind='project' row violates chk_task_kind at write time — a live V51 prod
-- smoke caught exactly that (POST /api/posts kind="project" would 500). The
-- established pattern (see V11 header) is: adding a PostKind wire value REQUIRES
-- widening chk_task_kind in the same migration. This backfills the miss.
--
-- Safety: purely additive — a WIDER CHECK can't be violated by any existing
-- row (every currently-valid kind stays valid; we only ADD 'project', which no
-- existing row uses). Zero rows touched, no backfill. The value list is copied
-- verbatim from the live prod constraint definition + 'project'.
--
-- H2 note: the create-drop test schema is built from entities and carries no
-- CHECK constraints, so unit tests never exercise this — it is validated by the
-- Postgres rehearsal + the live smoke, same as the V50 partial-index/CHECK.

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
            'project'
        )
    );
