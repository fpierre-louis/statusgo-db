-- Direct messages — one-on-one threads between two users.
-- Participants are identity emails (lowercase), stored in lexicographic
-- order (participant_a_email < participant_b_email) so one thread per
-- pair enforces itself via the unique constraint regardless of who
-- messaged first. Per-participant read watermarks live on the thread
-- (a_/b_ prefix matches the participant column the viewer occupies).

CREATE TABLE IF NOT EXISTS dm_thread (
    id                   BIGSERIAL PRIMARY KEY,
    participant_a_email  VARCHAR(255) NOT NULL,
    participant_b_email  VARCHAR(255) NOT NULL,
    created_at           TIMESTAMP NOT NULL DEFAULT now(),
    last_message_at      TIMESTAMP,
    a_last_read_at       TIMESTAMP,
    b_last_read_at       TIMESTAMP,
    CONSTRAINT uk_dm_thread_participants
        UNIQUE (participant_a_email, participant_b_email)
);

CREATE INDEX IF NOT EXISTS idx_dm_thread_a ON dm_thread (participant_a_email);
CREATE INDEX IF NOT EXISTS idx_dm_thread_b ON dm_thread (participant_b_email);

CREATE TABLE IF NOT EXISTS dm_message (
    id            BIGSERIAL PRIMARY KEY,
    thread_id     BIGINT NOT NULL REFERENCES dm_thread (id) ON DELETE CASCADE,
    sender_email  VARCHAR(255) NOT NULL,
    body          TEXT NOT NULL,
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_dm_message_thread_created
    ON dm_message (thread_id, created_at);
