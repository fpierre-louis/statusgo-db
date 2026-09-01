-- RENUMBERED V68 → V70 on 2026-09-01, and it had to be.
--
-- This shipped as V68 in e0aafc9, on top of 232a744 which had ALREADY taken
-- V68 (plan_activation_end) and had already applied it to production — so
-- `flyway_schema_history` holds version 68 = "plan activation end", success.
-- Two files claiming one version makes Flyway refuse to start at all:
--
--     FlywayException: Found more than one migration with version 68
--
-- and it refuses during BEAN CREATION, so the whole app fails to boot. That is
-- what took production down at 23:01 UTC (H10, 503s on api.sitprep.app).
--
-- The applied one keeps the number; this one moves. Renaming the applied one
-- instead would leave Flyway with a version recorded in the database and no
-- file to match it, which fails validation just as hard.
--
-- Nothing about the SQL below changed.

-- V68 - Per-circle cover image.
--
-- The group page's hero was a gradient derived from the group TYPE's emblem
-- tint, so every HOA looked like every other HOA. A circle can now carry its
-- own cover: either an uploaded image or one of the bundled presets, both
-- stored here as a URL.
--
-- Mirrors logo_image_url exactly — same length, same nullability, same meaning
-- for NULL (fall back to the type's default treatment) — because the two are
-- set by the same kind of action and there is no reason for them to differ.

ALTER TABLE groups
    ADD COLUMN IF NOT EXISTS cover_image_url varchar(1024);
