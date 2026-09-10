-- RC-2 · evidence that a person was actually ASKED to check in.
--
-- Before this table, requestCheckIn persisted nothing, so a member whose
-- notification category is muted (Lane.DROP writes no log row either) was never
-- asked, left no trace, and rendered identically on the roster to a member who
-- was asked and stayed silent. "No response" was being said about people nobody
-- had contacted.
--
-- A row is a record of the ASK only. Whether a message was dispatched, let alone
-- delivered or seen, is a separate dimension answered by notification_log and
-- deliberately not folded in here.
CREATE TABLE IF NOT EXISTS check_in_request (
    id                  BIGSERIAL PRIMARY KEY,
    group_id            VARCHAR(64)  NOT NULL,
    subject_email       VARCHAR(255) NOT NULL,
    window_started_at   TIMESTAMPTZ  NOT NULL,
    requested_at        TIMESTAMPTZ  NOT NULL,
    requested_by_email  VARCHAR(255)
);

-- The window is part of the identity: asking during last week's drill is not
-- asking during tonight's warning. A re-ask inside the same window upserts;
-- a new window always needs a new ask.
CREATE UNIQUE INDEX IF NOT EXISTS uk_check_in_request_group_subject_window
    ON check_in_request (group_id, subject_email, window_started_at);

-- The read the roster makes: every ask for this group at or after the window start.
CREATE INDEX IF NOT EXISTS idx_check_in_request_group_window
    ON check_in_request (group_id, window_started_at);
