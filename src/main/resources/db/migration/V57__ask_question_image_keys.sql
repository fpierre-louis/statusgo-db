-- Photo attachments on questions.
--
-- Mirrors ask_tip_image_keys exactly rather than inventing a second
-- convention: same column names, same @ElementCollection shape, same
-- @OrderColumn. Tips have carried images since the vertical shipped and
-- questions never could, which is why the composer has no photo affordance
-- and the list row has no thumbnail — there was nowhere to put the result.
--
-- ⚠ @ElementCollection binds to the PHYSICAL table name, so this table has to
-- exist before the entity mapping does. Compiling is not the same as the
-- schema existing: without this migration the app compiles and then fails at
-- boot on schema validation.
--
-- `ord` is included from the start. ask_tip_image_keys shipped without it and
-- needed V5 to add it plus V10 to backfill row order from ctid — an ordered
-- collection with no order column silently returns rows in whatever order the
-- database feels like. Not repeating that.
CREATE TABLE IF NOT EXISTS ask_question_image_keys (
    question_id BIGINT NOT NULL,
    image_key   VARCHAR(256),
    ord         INTEGER
);

-- The collection is always read by parent id; without this every thread load
-- scans the whole table.
CREATE INDEX IF NOT EXISTS idx_ask_question_image_keys_question
    ON ask_question_image_keys (question_id);
