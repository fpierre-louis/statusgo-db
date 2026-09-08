-- Alert history — "what was active near this location", recorded because it
-- cannot be fetched.
--
-- WHY THIS TABLE EXISTS AT ALL. Measured 2026-09-07 against
-- api.weather.gov/alerts?point=…&start=…: NWS retains roughly FIVE DAYS, not a
-- month. Oklahoma City returns the same 23 alerts whether you ask since
-- 2026-09-01 or since 2025-01-01, oldest always 2026-09-02 — `start` is
-- accepted and then ignored past retention. A month of history cannot be
-- fetched from upstream. It can only be recorded, and the ingest tick already
-- holds the whole national picture every five minutes and throws it away.
--
-- SIZING, MEASURED NOT GUESSED. 1,510 distinct NWS alerts nationally in the 24h
-- to 2026-09-07, median wire feature 2,741 bytes. At 30 days retention that is
-- ~45,000 rows and ~135 MB. Affordable on Standard-2X, and the reason
-- `alerts.history.retentionDays` is a knob rather than a constant.
--
-- WHY payload IS A BLOB. It holds the serialized AlertIngestService
-- .NormalizedAlert verbatim, so a history row rehydrates into the SAME object
-- the live feed maps, and history renders through the SAME
-- AlertFeedService.toCard pipeline — template copy, tier, safety policy and
-- all. Hand-picking columns would create a second, lossy representation of an
-- alert, which is the drift this epic has spent its time undoing. TEXT rather
-- than jsonb matches the codebase convention (ExternalPoiCache.payload,
-- AskQuestion.body) and keeps ddl-auto=validate happy.
--
-- WHY THE PREFILTER COLUMNS. 45,000 payloads cannot be deserialized per
-- request. centroid_lat/lng, broadcast and the token child table are a
-- NARROWING over the same inputs AlertIngestService.matchTypeFor tests — never
-- a second matching rule. matchTypeFor stays the only thing that decides
-- whether an alert applies to a point; these columns only decide which rows are
-- worth waking up to ask it about.

CREATE TABLE IF NOT EXISTS alert_history (
    alert_id      varchar(255) PRIMARY KEY,   -- CAP identifier; immutable per message
    source        varchar(16)  NOT NULL,      -- NWS | USGS | FEMA
    event         varchar(160),               -- NWS product name; null for USGS/FEMA
    severity      varchar(16),
    payload       text         NOT NULL,      -- serialized NormalizedAlert

    -- Tier-1 prefilter: exactly the vertex matchTypeFor's polygon branch tests
    -- (firstCoord). Null when the alert ships no geometry — 82% of NWS alerts.
    centroid_lat  double precision,
    centroid_lng  double precision,

    -- Tier-"always" prefilter: no UGC and no geometry means matchTypeFor
    -- returns BROADCAST for every point, so these rows can never be excluded.
    broadcast     boolean      NOT NULL DEFAULT false,

    first_seen_at timestamptz  NOT NULL,      -- first tick this id appeared in
    last_seen_at  timestamptz  NOT NULL       -- last tick it was still active
);

-- The retention sweep and the history window both range on last_seen_at.
CREATE INDEX IF NOT EXISTS idx_alert_history_last_seen
    ON alert_history (last_seen_at);

-- Tiers 2 and 3: the UGC zone codes an alert targets, plus their 2-char state
-- prefixes, in one bag. A point resolves to zones (tier 2) or, when the points
-- lookup is unavailable, to state prefixes (tier 3); either way the candidate
-- query is one IN against this table. Both kinds live together because
-- matchTypeFor reads them from the same `ugc` list.
CREATE TABLE IF NOT EXISTS alert_history_token (
    alert_id varchar(255) NOT NULL REFERENCES alert_history (alert_id) ON DELETE CASCADE,
    token    varchar(16)  NOT NULL,
    PRIMARY KEY (alert_id, token)
);

CREATE INDEX IF NOT EXISTS idx_alert_history_token_token
    ON alert_history_token (token);
