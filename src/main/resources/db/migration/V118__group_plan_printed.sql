-- Printables: "your plan changed since you printed it" (2026-10-21).
-- When a household prints its plan, the backend records when, and a SHA-256
-- fingerprint of the plan content that prints. Reading the plan document
-- compares the current fingerprint with this one, so additions, edits and
-- deletions are all caught without hooking every service that writes a plan
-- piece. Null on every row until someone prints: never printed means no
-- reminder, never a nag.
ALTER TABLE groups ADD COLUMN IF NOT EXISTS plan_last_printed_at TIMESTAMP;
ALTER TABLE groups ADD COLUMN IF NOT EXISTS plan_printed_fingerprint VARCHAR(80);
