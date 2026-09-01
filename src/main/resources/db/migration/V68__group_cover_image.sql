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
