-- Idempotency-Key cache for POST endpoints — audit P1-10
--
-- Short-TTL cache (default 24h) keyed by (caller, endpoint, client-supplied
-- header value). When the FE retries a flaky POST (network drop on the way
-- back, anxious double-tap), the second hit reads the original response and
-- returns it verbatim — no duplicate Post row, no duplicate FCM fanout.
-- Annotated controllers today: POST /api/posts, POST /api/plans/activations,
-- POST /api/group-posts.
--
-- The created_at index backs the @Scheduled sweeper in
-- IdempotencyKeyRepo / IdempotencyKeySweepService.
--
-- Hibernate ddl-auto=validate is live, so the column types and constraints
-- here must match the entity (io.sitprep.sitprepapi.domain.IdempotencyKey).

CREATE TABLE IF NOT EXISTS idempotency_keys (
    caller_email         VARCHAR(320) NOT NULL,
    endpoint             VARCHAR(200) NOT NULL,
    idem_key             VARCHAR(200) NOT NULL,
    response_status_code INTEGER      NOT NULL,
    response_body        TEXT         NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (caller_email, endpoint, idem_key)
);

CREATE INDEX IF NOT EXISTS idx_idem_created_at
    ON idempotency_keys (created_at);
