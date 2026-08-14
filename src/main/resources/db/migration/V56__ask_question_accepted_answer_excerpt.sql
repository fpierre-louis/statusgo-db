-- Denormalised first line of a question's ACCEPTED answer, so the Ask list can
-- show what the answer actually said rather than only that one exists.
--
-- Why denormalised rather than joined at read time: the list path deliberately
-- runs with includeAnswers=false, because loading answers per row costs an
-- answers query plus two batch lookups EACH — a 20-row list becomes 20x that.
-- The excerpt is written on the four paths that can change it (accept,
-- un-accept, delete-answer, edit-answer) and read for free.
--
-- Nullable by design: null means "no accepted answer", which is the common
-- case and the correct initial value for every existing row. No backfill —
-- the excerpt appears the next time an answer is accepted, and a backfill
-- would need to read every answer body to derive text we can regenerate for
-- free on the next write.
ALTER TABLE ask_question
    ADD COLUMN IF NOT EXISTS accepted_answer_excerpt VARCHAR(200);
