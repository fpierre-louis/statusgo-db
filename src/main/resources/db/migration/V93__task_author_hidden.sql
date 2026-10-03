-- Hazard reports are anonymous by default (hazard-reports HR6, owner 2026-10-03:
-- "Reported by a neighbor", opt-in Show my name). The server keeps
-- requester_email for limits, the duplicate fold and moderation; this flag stops
-- it reaching anyone but the author.
ALTER TABLE task ADD COLUMN IF NOT EXISTS author_hidden BOOLEAN NOT NULL DEFAULT FALSE;
-- The ruling is the default, so the reports already made follow it.
UPDATE task SET author_hidden = TRUE WHERE kind = 'hazard';
