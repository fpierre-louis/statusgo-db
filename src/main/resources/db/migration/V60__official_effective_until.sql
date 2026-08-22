-- Official posts — when the advisory stops being in effect.
--
-- Asked for twice. An advisory that says "until 8 tonight" had no field
-- carrying it, so the thread page's "In effect" row could not be built and
-- `stripStatusFor` on the FE had to return null for the whole official kind.
--
-- THIS IS NOT `pinned_until`, AND THE ENTITY CURRENTLY CLAIMS IT IS.
-- Post.java:384 comments pinnedUntil as "the design's expiresAt". It is a
-- different fact:
--   • pinned_until      — how long this post sits at the TOP OF THE FEED.
--                         A ranking decision the product makes.
--   • effective_until   — when the WARNING STOPS BEING TRUE.
--                         A fact about the world the issuer states.
-- They diverge in the ordinary case: a 24-hour pin on a three-day flood
-- warning would render "In effect until tomorrow" on a warning that runs
-- through Friday. Rendering a ranking decision as a public-safety fact is the
-- failure mode worth a separate column.
--
-- DERIVED FOR DISPATCHED ALERTS, CAPTURED FOR COMPOSED ONES.
-- The value is already on the wire and already stored: AlertPost.expires_at is
-- populated at AlertDispatchService:248 from the NWS `ends`/`expires`. It was
-- simply never copied onto the post row, so the feed never saw it. The service
-- change alongside this migration copies it at dispatch time, and
-- AgencyAlertService accepts an explicit value for human-composed alerts —
-- a city writing "boil order until Thursday" has no NWS feed to derive from.
--
-- ROW COUNT MEASURED BEFORE APPLYING: `task` = 12,133 rows, of which
-- kind='official' = ZERO. Nullable add, no backfill, no rewrite. Every existing
-- row keeps NULL, which reads as "until further notice" — the honest default
-- for an advisory with no stated end.

ALTER TABLE task ADD COLUMN IF NOT EXISTS effective_until TIMESTAMP(6) WITH TIME ZONE;

COMMENT ON COLUMN task.effective_until IS
    'Official posts: when the advisory stops being in effect. NOT pinned_until, '
    'which is feed placement. NULL = until further notice.';
