-- Baseline captured 2026-06-05 from Hibernate entity definitions.
-- TIGHTEN BEFORE PUBLIC LAUNCH via real pg_dump against Heroku Postgres
-- (current dev environment blocks port 5432, baseline is structural only).
--
-- On the production Heroku database this file does NOT execute: Flyway is
-- configured with `baseline-on-migrate: true` + `baseline-version: 1` so it
-- stamps the schema_history at V1 without running this script. The script
-- is the structural reference for fresh-environment bootstraps (CI / local
-- spin-up against a clean Postgres) and the authoritative description of
-- the schema at the moment Hibernate ddl-auto flipped from update to
-- validate. Every subsequent migration ships as V{n}__name.sql.
--
-- CREATE TABLE IF NOT EXISTS everywhere so a fresh DB bootstraps and a
-- pre-existing DB (the only one Heroku actually has) is left untouched.
-- Lengths/nullability/defaults mirror @Column annotations; the column set
-- and the @ElementCollection / @CollectionTable side tables are exhaustive
-- across all 46 @Entity classes under io.sitprep.sitprepapi.domain.
-- Indexes/uniqueness constraints declared via @Index/@UniqueConstraint
-- annotations are included; legacy indexes added ad-hoc by Hibernate
-- ddl-auto under prior runs are documented as comments where known.

-- =============================================================
-- 1. user_info + element-collection side tables
-- =============================================================
CREATE TABLE IF NOT EXISTS user_info (
    user_id                                       VARCHAR(255) PRIMARY KEY,
    firebase_uid                                  VARCHAR(255) UNIQUE,
    user_first_name                               VARCHAR(255) NOT NULL,
    user_last_name                                VARCHAR(255) NOT NULL,
    user_email                                    VARCHAR(255) NOT NULL UNIQUE,
    title                                         VARCHAR(255),
    phone                                         VARCHAR(255),
    address                                       VARCHAR(255),
    longitude                                     VARCHAR(255),
    latitude                                      VARCHAR(255),
    user_status                                   VARCHAR(255),
    user_status_last_updated                      TIMESTAMP,
    status_color                                  VARCHAR(255),
    profile_image_url                             VARCHAR(255),
    fcm_token                                     VARCHAR(255),
    guest_account                                 BOOLEAN DEFAULT FALSE,
    guest_created_at                              TIMESTAMP,
    guest_expiry_reminder_sent_at                 TIMESTAMP,
    searchable                                    BOOLEAN DEFAULT FALSE,
    base_household_id                             VARCHAR(255),
    active_group_alert_count                      INTEGER,
    group_alert_last_updated                      TIMESTAMP,
    subscription                                  VARCHAR(255),
    subscription_package                          VARCHAR(255),
    date_subscribed                               TIMESTAMP,
    last_active_at                                TIMESTAMP,
    last_assessment_at                            TIMESTAMP,
    onboarding_completed_at                       TIMESTAMP,
    onboarding_terms_accepted_at                  TIMESTAMP,
    onboarding_location_enabled_at                TIMESTAMP,
    onboarding_notifications_enabled_at           TIMESTAMP,
    assessment_summary_json                       TEXT,
    last_known_lat                                DOUBLE PRECISION,
    last_known_lng                                DOUBLE PRECISION,
    last_known_location_at                        TIMESTAMP,
    verified_publisher                            BOOLEAN NOT NULL DEFAULT FALSE,
    verified_publisher_kind                       VARCHAR(32),
    verified_since                                TIMESTAMP,
    verified_by                                   VARCHAR(255),
    verified_publisher_service_area               VARCHAR(400),
    verified_publisher_permanent_address          VARCHAR(400),
    verified_publisher_temporary_event_address    VARCHAR(400),
    verified_publisher_emergency_posting_enabled  BOOLEAN NOT NULL DEFAULT FALSE,
    verified_publisher_group_id                   VARCHAR(80),
    bio                                           VARCHAR(200),
    cover_image_url                               VARCHAR(500),
    profile_visibility                            VARCHAR(32) NOT NULL DEFAULT 'circles'
);

CREATE TABLE IF NOT EXISTS user_managed_group_ids (
    user_id          VARCHAR(255) NOT NULL,
    managed_group_id VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS user_joined_group_ids (
    user_id         VARCHAR(255) NOT NULL,
    joined_group_id VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS user_info_group_location_sharing (
    user_info_id VARCHAR(255) NOT NULL,
    group_id     VARCHAR(64)  NOT NULL,
    mode         VARCHAR(32)
);

-- =============================================================
-- 2. groups (Group entity) + side tables
-- =============================================================
CREATE TABLE IF NOT EXISTS groups (
    group_id                VARCHAR(255) PRIMARY KEY,
    alert                   VARCHAR(255),
    active_hazard_type      VARCHAR(255),
    alert_activated_at      TIMESTAMP,
    checkin_reminders_fired INTEGER,
    created_at              TIMESTAMP,
    description             VARCHAR(255),
    group_code              VARCHAR(255),
    group_name              VARCHAR(255),
    group_type              VARCHAR(255),
    last_updated_by         VARCHAR(255),
    member_count            INTEGER,
    privacy                 VARCHAR(255),
    updated_at              TIMESTAMP,
    address                 VARCHAR(255),
    longitude               VARCHAR(255),
    latitude                VARCHAR(255),
    zip_code                VARCHAR(255),
    owner_name              VARCHAR(255),
    owner_email             VARCHAR(255),
    plan_tier               VARCHAR(255),
    logo_image_url          VARCHAR(1024),
    stripe_customer_id      VARCHAR(255),
    stripe_subscription_id  VARCHAR(255),
    subscription_status     VARCHAR(255),
    plan_last_confirmed_at  TIMESTAMP
);

CREATE TABLE IF NOT EXISTS group_admin_emails (
    group_id    VARCHAR(255) NOT NULL,
    admin_email VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS group_member_emails (
    group_id     VARCHAR(255) NOT NULL,
    member_email VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS group_pending_member_emails (
    group_id             VARCHAR(255) NOT NULL,
    pending_member_email VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS group_sub_group_ids (
    group_id     VARCHAR(255) NOT NULL,
    sub_group_id VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS group_parent_group_ids (
    group_id        VARCHAR(255) NOT NULL,
    parent_group_id VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS group_challenge_progress (
    group_id  VARCHAR(255) NOT NULL,
    week_key  VARCHAR(16)  NOT NULL,
    completed BOOLEAN
);

-- =============================================================
-- 3. task (Post entity — legacy table name) + side tables
-- =============================================================
CREATE TABLE IF NOT EXISTS task (
    id                   BIGSERIAL PRIMARY KEY,
    group_id             VARCHAR(255),
    requester_email      VARCHAR(255) NOT NULL,
    authored_as_group_id VARCHAR(64),
    claimed_by_group_id  VARCHAR(255),
    claimed_by_email     VARCHAR(255),
    assignee_email       VARCHAR(255),
    assigned_by_email    VARCHAR(255),
    assigned_at          TIMESTAMP,
    status               VARCHAR(16) NOT NULL,
    priority             VARCHAR(16) NOT NULL,
    kind                 VARCHAR(32) NOT NULL DEFAULT 'ask',
    title                VARCHAR(200),
    description          VARCHAR(4096),
    latitude             DOUBLE PRECISION,
    longitude            DOUBLE PRECISION,
    zip_bucket           VARCHAR(8),
    place_label          VARCHAR(128),
    due_at               TIMESTAMP,
    reminder_sent_at     TIMESTAMP,
    created_at           TIMESTAMP NOT NULL,
    updated_at           TIMESTAMP NOT NULL,
    claimed_at           TIMESTAMP,
    completed_at         TIMESTAMP,
    parent_task_id       BIGINT,
    sponsored            BOOLEAN NOT NULL DEFAULT FALSE,
    crisis_relevant      BOOLEAN NOT NULL DEFAULT FALSE,
    sponsored_until      TIMESTAMP,
    sponsored_by         VARCHAR(128),
    price                NUMERIC(10,2),
    is_free              BOOLEAN NOT NULL DEFAULT FALSE,
    payment_methods_json TEXT
);

CREATE INDEX IF NOT EXISTS idx_task_group_status ON task (group_id, status);
CREATE INDEX IF NOT EXISTS idx_task_zip_status   ON task (zip_bucket, status);
CREATE INDEX IF NOT EXISTS idx_task_requester    ON task (requester_email);
CREATE INDEX IF NOT EXISTS idx_task_claimer      ON task (claimed_by_email);

CREATE TABLE IF NOT EXISTS task_image_keys (
    task_id   BIGINT NOT NULL,
    image_key VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS task_tags (
    task_id BIGINT NOT NULL,
    tag     VARCHAR(255)
);

-- =============================================================
-- 4. task_comment (PostComment) + community-feed reactions
-- =============================================================
CREATE TABLE IF NOT EXISTS task_comment (
    id         BIGSERIAL PRIMARY KEY,
    task_id    BIGINT NOT NULL,
    author     VARCHAR(255) NOT NULL,
    content    TEXT NOT NULL,
    timestamp  TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    edited_at  TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_task_comment_task_id    ON task_comment (task_id);
CREATE INDEX IF NOT EXISTS idx_task_comment_updated_at ON task_comment (updated_at);

CREATE TABLE IF NOT EXISTS task_reaction (
    id         BIGSERIAL PRIMARY KEY,
    task_id    BIGINT NOT NULL,
    user_email VARCHAR(255) NOT NULL,
    emoji      VARCHAR(32) NOT NULL,
    added_at   TIMESTAMP NOT NULL,
    CONSTRAINT uk_task_reaction_task_user_emoji UNIQUE (task_id, user_email, emoji)
);
CREATE INDEX IF NOT EXISTS idx_task_reaction_task ON task_reaction (task_id);
CREATE INDEX IF NOT EXISTS idx_task_reaction_user ON task_reaction (user_email);

CREATE TABLE IF NOT EXISTS post_comment_reaction (
    id              BIGSERIAL PRIMARY KEY,
    post_comment_id BIGINT NOT NULL,
    user_email      VARCHAR(255) NOT NULL,
    emoji           VARCHAR(32) NOT NULL,
    added_at        TIMESTAMP NOT NULL,
    CONSTRAINT uk_post_comment_reaction_comment_user_emoji UNIQUE (post_comment_id, user_email, emoji)
);
CREATE INDEX IF NOT EXISTS idx_post_comment_reaction_comment ON post_comment_reaction (post_comment_id);
CREATE INDEX IF NOT EXISTS idx_post_comment_reaction_user    ON post_comment_reaction (user_email);

-- =============================================================
-- 5. post (GroupPost) + group-post side tables and reactions
-- =============================================================
CREATE TABLE IF NOT EXISTS post (
    id             BIGSERIAL PRIMARY KEY,
    author         VARCHAR(255),
    content        VARCHAR(255),
    group_id       VARCHAR(255),
    group_name     VARCHAR(255),
    timestamp      TIMESTAMP,
    image_key      VARCHAR(255),
    edited_at      TIMESTAMP,
    updated_at     TIMESTAMP,
    comments_count INTEGER NOT NULL DEFAULT 0,
    pinned_at      TIMESTAMP,
    pinned_by      VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_post_group_ts ON post (group_id, timestamp);

CREATE TABLE IF NOT EXISTS group_post_tags (
    group_post_id BIGINT NOT NULL,
    tags          VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS group_post_mentions (
    group_post_id BIGINT NOT NULL,
    mentions      VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS comment (
    id         BIGSERIAL PRIMARY KEY,
    post_id    BIGINT NOT NULL,
    author     VARCHAR(255) NOT NULL,
    content    TEXT NOT NULL,
    timestamp  TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    edited_at  TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_comment_post_id    ON comment (post_id);
CREATE INDEX IF NOT EXISTS idx_comment_updated_at ON comment (updated_at);

CREATE TABLE IF NOT EXISTS post_reaction (
    id         BIGSERIAL PRIMARY KEY,
    post_id    BIGINT NOT NULL,
    user_email VARCHAR(255) NOT NULL,
    emoji      VARCHAR(32) NOT NULL,
    added_at   TIMESTAMP NOT NULL,
    CONSTRAINT uk_post_reaction_post_user_emoji UNIQUE (post_id, user_email, emoji)
);
CREATE INDEX IF NOT EXISTS idx_post_reaction_post ON post_reaction (post_id);
CREATE INDEX IF NOT EXISTS idx_post_reaction_user ON post_reaction (user_email);

CREATE TABLE IF NOT EXISTS group_post_comment_reaction (
    id                    BIGSERIAL PRIMARY KEY,
    group_post_comment_id BIGINT NOT NULL,
    user_email            VARCHAR(255) NOT NULL,
    emoji                 VARCHAR(32) NOT NULL,
    added_at              TIMESTAMP NOT NULL,
    CONSTRAINT uk_group_post_comment_reaction_comment_user_emoji UNIQUE (group_post_comment_id, user_email, emoji)
);
CREATE INDEX IF NOT EXISTS idx_group_post_comment_reaction_comment ON group_post_comment_reaction (group_post_comment_id);
CREATE INDEX IF NOT EXISTS idx_group_post_comment_reaction_user    ON group_post_comment_reaction (user_email);

-- =============================================================
-- 6. household auxiliary entities
-- =============================================================
CREATE TABLE IF NOT EXISTS household_event (
    id            BIGSERIAL PRIMARY KEY,
    household_id  VARCHAR(64) NOT NULL,
    kind          VARCHAR(32) NOT NULL,
    at            TIMESTAMP NOT NULL,
    actor_email   VARCHAR(255),
    payload_json  TEXT
);
CREATE INDEX IF NOT EXISTS idx_household_event_hh_at ON household_event (household_id, at);
CREATE INDEX IF NOT EXISTS idx_household_event_kind  ON household_event (kind);

CREATE TABLE IF NOT EXISTS household_manual_member (
    id            VARCHAR(64) PRIMARY KEY,
    household_id  VARCHAR(64) NOT NULL,
    name          VARCHAR(120) NOT NULL,
    relationship  VARCHAR(120),
    age           INTEGER,
    is_adult      BOOLEAN NOT NULL DEFAULT FALSE,
    photo_url     VARCHAR(1024),
    created_at    TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_manual_member_household ON household_manual_member (household_id);

CREATE TABLE IF NOT EXISTS household_accompaniment (
    id               BIGSERIAL PRIMARY KEY,
    household_id     VARCHAR(64) NOT NULL,
    supervisor_kind  VARCHAR(16) NOT NULL,
    supervisor_id    VARCHAR(255) NOT NULL,
    accompanied_kind VARCHAR(16) NOT NULL,
    accompanied_id   VARCHAR(255) NOT NULL,
    since            TIMESTAMP NOT NULL,
    pending          BOOLEAN NOT NULL,
    CONSTRAINT uk_household_accompaniment_target UNIQUE (household_id, accompanied_kind, accompanied_id)
);
CREATE INDEX IF NOT EXISTS idx_household_accompaniment_household
    ON household_accompaniment (household_id);
CREATE INDEX IF NOT EXISTS idx_household_accompaniment_supervisor
    ON household_accompaniment (household_id, supervisor_kind, supervisor_id);

CREATE TABLE IF NOT EXISTS household_pet (
    id            VARCHAR(64) PRIMARY KEY,
    household_id  VARCHAR(64) NOT NULL,
    name          VARCHAR(120) NOT NULL,
    species       VARCHAR(40),
    notes         VARCHAR(1024),
    photo_url     VARCHAR(1024),
    created_at    TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_household_pet_household ON household_pet (household_id);

CREATE TABLE IF NOT EXISTS household_ritual (
    id                BIGSERIAL PRIMARY KEY,
    household_id      VARCHAR(64) NOT NULL,
    kind              VARCHAR(32) NOT NULL,
    schedule_spec     VARCHAR(64) NOT NULL,
    timezone          VARCHAR(64) NOT NULL,
    opted_in_by_email VARCHAR(255) NOT NULL,
    last_fired_at     TIMESTAMP,
    paused_until      TIMESTAMP,
    created_at        TIMESTAMP NOT NULL,
    updated_at        TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_household_ritual_hh   ON household_ritual (household_id);
CREATE INDEX IF NOT EXISTS idx_household_ritual_kind ON household_ritual (kind);

CREATE TABLE IF NOT EXISTS household_invite_request (
    id              VARCHAR(36) PRIMARY KEY,
    household_id    VARCHAR(64) NOT NULL,
    requester_email VARCHAR(255) NOT NULL,
    candidate_email VARCHAR(255) NOT NULL,
    status          VARCHAR(16) NOT NULL,
    created_at      TIMESTAMP NOT NULL,
    resolved_at     TIMESTAMP,
    resolver_email  VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_hir_household_status ON household_invite_request (household_id, status);
CREATE INDEX IF NOT EXISTS idx_hir_candidate_status ON household_invite_request (candidate_email, status);

-- =============================================================
-- 7. plan activations + acks
-- =============================================================
CREATE TABLE IF NOT EXISTS plan_activations (
    activation_id    VARCHAR(255) PRIMARY KEY,
    owner_email      VARCHAR(255) NOT NULL,
    owner_user_id    VARCHAR(255),
    owner_name       VARCHAR(255),
    meeting_place_id BIGINT,
    evac_plan_id     BIGINT,
    meeting_mode     VARCHAR(255),
    evac_mode        VARCHAR(255),
    message_preview  VARCHAR(2048),
    lat              DOUBLE PRECISION,
    lng              DOUBLE PRECISION,
    activated_at     TIMESTAMP NOT NULL,
    expires_at       TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS plan_activation_household_members (
    activation_id        VARCHAR(255) NOT NULL,
    household_member_id  VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS plan_activation_contact_ids (
    activation_id VARCHAR(255) NOT NULL,
    contact_id    BIGINT
);

CREATE TABLE IF NOT EXISTS plan_activation_contact_group_ids (
    activation_id     VARCHAR(255) NOT NULL,
    contact_group_id  BIGINT
);

CREATE TABLE IF NOT EXISTS plan_activation_acks (
    id              BIGSERIAL PRIMARY KEY,
    activation_id   VARCHAR(255) NOT NULL,
    recipient_email VARCHAR(255) NOT NULL,
    recipient_name  VARCHAR(255),
    status          VARCHAR(255) NOT NULL,
    lat             DOUBLE PRECISION,
    lng             DOUBLE PRECISION,
    acked_at        TIMESTAMP NOT NULL,
    CONSTRAINT uk_activation_recipient UNIQUE (activation_id, recipient_email)
);
CREATE INDEX IF NOT EXISTS idx_ack_activation ON plan_activation_acks (activation_id);

-- =============================================================
-- 8. notification_log
-- =============================================================
CREATE TABLE IF NOT EXISTS notification_log (
    id              BIGSERIAL PRIMARY KEY,
    recipient_email VARCHAR(255),
    type            VARCHAR(255),
    token           VARCHAR(255),
    title           VARCHAR(255),
    body            VARCHAR(255),
    reference_id    VARCHAR(255),
    target_url      VARCHAR(255),
    additional_data TEXT,
    timestamp       TIMESTAMP,
    success         BOOLEAN NOT NULL DEFAULT FALSE,
    error_message   VARCHAR(255),
    read_at         TIMESTAMP,
    lane            VARCHAR(16),
    category        VARCHAR(32),
    archived_at     TIMESTAMP,
    actor_user_id   VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_notif_recipient_ts     ON notification_log (recipient_email, timestamp);
CREATE INDEX IF NOT EXISTS idx_notif_type_ts          ON notification_log (type, timestamp);
CREATE INDEX IF NOT EXISTS idx_notif_recipient_unread ON notification_log (recipient_email, read_at);

-- =============================================================
-- 9. Ask surface (question / answer / tip / vote / bookmark)
-- =============================================================
CREATE TABLE IF NOT EXISTS ask_question (
    id                  BIGSERIAL PRIMARY KEY,
    author_email        VARCHAR(320) NOT NULL,
    title               VARCHAR(200) NOT NULL,
    body                TEXT NOT NULL,
    latitude            DOUBLE PRECISION,
    longitude           DOUBLE PRECISION,
    zip_bucket          VARCHAR(8),
    place_label         VARCHAR(128),
    vote_score          INTEGER NOT NULL DEFAULT 0,
    view_count          BIGINT NOT NULL DEFAULT 0,
    answer_count        INTEGER NOT NULL DEFAULT 0,
    accepted_answer_id  BIGINT,
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP NOT NULL,
    edited_at           TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ask_question_zip     ON ask_question (zip_bucket);
CREATE INDEX IF NOT EXISTS idx_ask_question_author  ON ask_question (author_email);
CREATE INDEX IF NOT EXISTS idx_ask_question_score   ON ask_question (vote_score);
CREATE INDEX IF NOT EXISTS idx_ask_question_created ON ask_question (created_at);

CREATE TABLE IF NOT EXISTS ask_question_tags (
    question_id BIGINT NOT NULL,
    tag         VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS ask_question_hazards (
    question_id BIGINT NOT NULL,
    hazard      VARCHAR(32)
);

CREATE TABLE IF NOT EXISTS ask_answer (
    id           BIGSERIAL PRIMARY KEY,
    question_id  BIGINT NOT NULL,
    author_email VARCHAR(320) NOT NULL,
    body         TEXT NOT NULL,
    vote_score   INTEGER NOT NULL DEFAULT 0,
    created_at   TIMESTAMP NOT NULL,
    updated_at   TIMESTAMP NOT NULL,
    edited_at    TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ask_answer_question ON ask_answer (question_id);
CREATE INDEX IF NOT EXISTS idx_ask_answer_author   ON ask_answer (author_email);
CREATE INDEX IF NOT EXISTS idx_ask_answer_score    ON ask_answer (vote_score);

CREATE TABLE IF NOT EXISTS ask_tip (
    id              BIGSERIAL PRIMARY KEY,
    author_email    VARCHAR(320) NOT NULL,
    title           VARCHAR(200) NOT NULL,
    body            TEXT NOT NULL,
    cover_image_key VARCHAR(256),
    latitude        DOUBLE PRECISION,
    longitude       DOUBLE PRECISION,
    zip_bucket      VARCHAR(8),
    place_label     VARCHAR(128),
    vote_score      INTEGER NOT NULL DEFAULT 0,
    view_count      BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL,
    edited_at       TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ask_tip_zip     ON ask_tip (zip_bucket);
CREATE INDEX IF NOT EXISTS idx_ask_tip_author  ON ask_tip (author_email);
CREATE INDEX IF NOT EXISTS idx_ask_tip_score   ON ask_tip (vote_score);
CREATE INDEX IF NOT EXISTS idx_ask_tip_created ON ask_tip (created_at);

CREATE TABLE IF NOT EXISTS ask_tip_tags (
    tip_id BIGINT NOT NULL,
    tag    VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS ask_tip_hazards (
    tip_id BIGINT NOT NULL,
    hazard VARCHAR(32)
);

CREATE TABLE IF NOT EXISTS ask_tip_image_keys (
    tip_id    BIGINT NOT NULL,
    image_key VARCHAR(256)
);

CREATE TABLE IF NOT EXISTS ask_vote (
    id          BIGSERIAL PRIMARY KEY,
    target_type VARCHAR(16) NOT NULL,
    target_id   BIGINT NOT NULL,
    voter_email VARCHAR(320) NOT NULL,
    value       INTEGER NOT NULL,
    created_at  TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL,
    CONSTRAINT uk_ask_vote_target_voter UNIQUE (target_type, target_id, voter_email)
);
CREATE INDEX IF NOT EXISTS idx_ask_vote_target ON ask_vote (target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_ask_vote_voter  ON ask_vote (voter_email);

CREATE TABLE IF NOT EXISTS ask_bookmark (
    id          BIGSERIAL PRIMARY KEY,
    user_email  VARCHAR(320) NOT NULL,
    target_type VARCHAR(16)  NOT NULL,
    target_key  VARCHAR(128) NOT NULL,
    created_at  TIMESTAMP NOT NULL,
    CONSTRAINT uk_ask_bookmark_user_target UNIQUE (user_email, target_type, target_key)
);
CREATE INDEX IF NOT EXISTS idx_ask_bookmark_user   ON ask_bookmark (user_email, created_at);
CREATE INDEX IF NOT EXISTS idx_ask_bookmark_target ON ask_bookmark (target_type, target_key);

-- =============================================================
-- 10. Follow / Block edges
-- =============================================================
CREATE TABLE IF NOT EXISTS follow (
    id              BIGSERIAL PRIMARY KEY,
    follower_email  VARCHAR(255) NOT NULL,
    followed_email  VARCHAR(255) NOT NULL,
    since           TIMESTAMP NOT NULL,
    CONSTRAINT uk_follow_follower_followed UNIQUE (follower_email, followed_email)
);
CREATE INDEX IF NOT EXISTS idx_follow_follower ON follow (follower_email);
CREATE INDEX IF NOT EXISTS idx_follow_followed ON follow (followed_email);

CREATE TABLE IF NOT EXISTS block (
    id             BIGSERIAL PRIMARY KEY,
    blocker_email  VARCHAR(255) NOT NULL,
    blocked_email  VARCHAR(255) NOT NULL,
    since          TIMESTAMP NOT NULL,
    CONSTRAINT uk_block_blocker_blocked UNIQUE (blocker_email, blocked_email)
);
CREATE INDEX IF NOT EXISTS idx_block_blocker ON block (blocker_email);
CREATE INDEX IF NOT EXISTS idx_block_blocked ON block (blocked_email);

-- =============================================================
-- 11. Plan-builder entities (demographic / evac / meeting / origin)
-- =============================================================
CREATE TABLE IF NOT EXISTS demographic (
    id           BIGSERIAL PRIMARY KEY,
    owner_email  VARCHAR(255),
    household_id VARCHAR(255),
    infants      INTEGER NOT NULL DEFAULT 0,
    adults       INTEGER NOT NULL DEFAULT 0,
    teens        INTEGER NOT NULL DEFAULT 0,
    kids         INTEGER NOT NULL DEFAULT 0,
    dogs         INTEGER NOT NULL DEFAULT 0,
    cats         INTEGER NOT NULL DEFAULT 0,
    pets         INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS demographic_admin_emails (
    demographic_id BIGINT NOT NULL,
    admin_emails   VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS evacuation_plan (
    id                   BIGSERIAL PRIMARY KEY,
    owner_email          VARCHAR(255),
    household_id         VARCHAR(255),
    name                 VARCHAR(255),
    origin               VARCHAR(255),
    destination          VARCHAR(255),
    deploy               BOOLEAN NOT NULL DEFAULT FALSE,
    shelter_name         VARCHAR(255),
    shelter_address      VARCHAR(255),
    shelter_phone_number VARCHAR(255),
    lat                  DOUBLE PRECISION,
    lng                  DOUBLE PRECISION,
    travel_mode          VARCHAR(255),
    shelter_info         VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS meeting_place (
    id              BIGSERIAL PRIMARY KEY,
    owner_email     VARCHAR(255),
    household_id    VARCHAR(255),
    name            VARCHAR(255),
    location        VARCHAR(255),
    address         VARCHAR(255),
    phone_number    VARCHAR(255),
    tier_key        VARCHAR(255),
    additional_info VARCHAR(2048),
    lat             DOUBLE PRECISION,
    lng             DOUBLE PRECISION,
    deploy          BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS origin_location (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(255),
    address      VARCHAR(255),
    lat          DOUBLE PRECISION,
    lng          DOUBLE PRECISION,
    user_email   VARCHAR(255) NOT NULL,
    household_id VARCHAR(255)
);

-- =============================================================
-- 12. Meal plan (v2 tables)
-- =============================================================
CREATE TABLE IF NOT EXISTS meal_plan_data_v2 (
    id                       BIGSERIAL PRIMARY KEY,
    owner_email              VARCHAR(255) NOT NULL,
    household_id             VARCHAR(255),
    plan_duration_quantity   INTEGER NOT NULL DEFAULT 0,
    plan_duration_unit       VARCHAR(255),
    number_of_menu_options   INTEGER NOT NULL DEFAULT 0,
    selected_items_json      TEXT
);

CREATE TABLE IF NOT EXISTS meal_plan_v2 (
    id                BIGSERIAL PRIMARY KEY,
    meal_plan_data_id BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS meal_plan_meals_v2 (
    meal_plan_id BIGINT NOT NULL,
    meal_type    VARCHAR(255) NOT NULL,
    meal_name    VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS meal_plan_ingredients_v2 (
    meal_plan_id BIGINT NOT NULL,
    meal_type    VARCHAR(255) NOT NULL,
    ingredients  VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS ingredient_v2 (
    id        BIGSERIAL PRIMARY KEY,
    meal_type VARCHAR(255),
    name      VARCHAR(255)
);

-- =============================================================
-- 13. Saved locations
-- =============================================================
CREATE TABLE IF NOT EXISTS user_saved_location (
    id          BIGSERIAL PRIMARY KEY,
    owner_email VARCHAR(255) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    address     VARCHAR(255),
    latitude    DOUBLE PRECISION NOT NULL,
    longitude   DOUBLE PRECISION NOT NULL,
    is_home     BOOLEAN NOT NULL DEFAULT FALSE,
    city        VARCHAR(255),
    region      VARCHAR(255),
    state       VARCHAR(255),
    country     VARCHAR(255),
    zip_bucket  VARCHAR(255),
    created_at  TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_usl_owner      ON user_saved_location (owner_email);
CREATE INDEX IF NOT EXISTS idx_usl_owner_home ON user_saved_location (owner_email, is_home);

-- =============================================================
-- 14. Emergency contacts (groups + items)
-- =============================================================
CREATE TABLE IF NOT EXISTS emergency_contact_groups (
    id           BIGSERIAL PRIMARY KEY,
    owner_email  VARCHAR(255) NOT NULL,
    household_id VARCHAR(255),
    name         VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS emergency_contacts (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(255),
    phone         VARCHAR(255),
    email         VARCHAR(255),
    address       VARCHAR(255),
    role          VARCHAR(255),
    medical_info  VARCHAR(255),
    radio_channel VARCHAR(255),
    subject_type  VARCHAR(255),
    subject_id    VARCHAR(255),
    subject_name  VARCHAR(255),
    group_id      BIGINT NOT NULL
);

-- =============================================================
-- 15. Alert mode + alert post (sponsored / mode state)
-- =============================================================
CREATE TABLE IF NOT EXISTS alert_mode_state (
    zip_bucket             VARCHAR(32) PRIMARY KEY,
    state                  VARCHAR(16) NOT NULL,
    triggers_json          TEXT,
    entered_at             TIMESTAMP NOT NULL,
    last_trigger_seen      TIMESTAMP,
    hysteresis_expires_at  TIMESTAMP,
    updated_at             TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_amode_state ON alert_mode_state (state);
CREATE INDEX IF NOT EXISTS idx_amode_dwell ON alert_mode_state (hysteresis_expires_at);

CREATE TABLE IF NOT EXISTS alert_post (
    id          BIGSERIAL PRIMARY KEY,
    alert_id    VARCHAR(128) NOT NULL,
    hazard_type VARCHAR(32),
    geocell_id  VARCHAR(32) NOT NULL,
    post_id     BIGINT NOT NULL,
    created_at  TIMESTAMP NOT NULL,
    expires_at  TIMESTAMP,
    resolved_at TIMESTAMP,
    CONSTRAINT uk_alert_post_dedup UNIQUE (alert_id, geocell_id)
);
CREATE INDEX IF NOT EXISTS idx_alert_post_alert      ON alert_post (alert_id);
CREATE INDEX IF NOT EXISTS idx_alert_post_unresolved ON alert_post (resolved_at, alert_id);

-- =============================================================
-- 16. User alert preferences
-- =============================================================
CREATE TABLE IF NOT EXISTS user_alert_preference (
    user_email           VARCHAR(255) PRIMARY KEY,
    push_enabled         BOOLEAN NOT NULL,
    inbox_enabled        BOOLEAN NOT NULL,
    nws_alerts           BOOLEAN NOT NULL,
    earthquakes          BOOLEAN NOT NULL,
    wildfires            BOOLEAN NOT NULL,
    group_alerts         BOOLEAN NOT NULL,
    plan_activations     BOOLEAN NOT NULL,
    activation_acks      BOOLEAN NOT NULL,
    task_assignments     BOOLEAN NOT NULL,
    pending_members      BOOLEAN NOT NULL,
    quiet_hours_enabled  BOOLEAN NOT NULL,
    quiet_start          TIME    NOT NULL,
    quiet_end            TIME    NOT NULL,
    timezone             VARCHAR(64) NOT NULL,
    updated_at           TIMESTAMP NOT NULL
);

-- =============================================================
-- 17. Group mute + read state
-- =============================================================
CREATE TABLE IF NOT EXISTS group_mute_pref (
    id             BIGSERIAL PRIMARY KEY,
    user_email     VARCHAR(255) NOT NULL,
    group_id       VARCHAR(255) NOT NULL,
    muted_until    TIMESTAMP,
    quiet_start    INTEGER,
    quiet_end      INTEGER,
    quiet_timezone VARCHAR(64),
    updated_at     TIMESTAMP NOT NULL,
    CONSTRAINT uq_gmp_user_group UNIQUE (user_email, group_id)
);
CREATE INDEX IF NOT EXISTS idx_gmp_user ON group_mute_pref (user_email);

CREATE TABLE IF NOT EXISTS group_read_state (
    id           BIGSERIAL PRIMARY KEY,
    user_email   VARCHAR(255) NOT NULL,
    group_id     VARCHAR(255) NOT NULL,
    last_read_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_grs_user_group UNIQUE (user_email, group_id)
);
CREATE INDEX IF NOT EXISTS idx_grs_user ON group_read_state (user_email);

-- =============================================================
-- 18. Group invites
-- =============================================================
CREATE TABLE IF NOT EXISTS group_invites (
    invite_id        VARCHAR(255) PRIMARY KEY,
    group_id         VARCHAR(255) NOT NULL,
    issued_by_email  VARCHAR(255) NOT NULL,
    issued_at        TIMESTAMP NOT NULL,
    expires_at       TIMESTAMP NOT NULL,
    max_uses         INTEGER,
    used_count       INTEGER NOT NULL DEFAULT 0,
    revoked_at       TIMESTAMP
);

-- =============================================================
-- 19. Verification application
-- =============================================================
CREATE TABLE IF NOT EXISTS verification_application (
    id                                BIGSERIAL PRIMARY KEY,
    group_id                          VARCHAR(80) NOT NULL,
    applicant_email                   VARCHAR(160) NOT NULL,
    account_type                      VARCHAR(64),
    legal_name                        VARCHAR(180),
    public_name                       VARCHAR(240),
    website                           VARCHAR(400),
    official_email                    VARCHAR(180),
    address_or_jurisdiction           VARCHAR(400),
    service_area                      VARCHAR(400),
    primary_admin                     VARCHAR(240),
    backup_contact                    VARCHAR(240),
    posting_intent                    VARCHAR(500),
    proof_links                       VARCHAR(1000),
    notes                             VARCHAR(1000),
    status                            VARCHAR(24) NOT NULL,
    reviewer_notes                    VARCHAR(1000),
    reviewer_email                    VARCHAR(160),
    verified_kind                     VARCHAR(40),
    approved_publisher_email          VARCHAR(160),
    publisher_service_area            VARCHAR(400),
    publisher_permanent_address       VARCHAR(400),
    publisher_temporary_event_address VARCHAR(400),
    emergency_posting_enabled         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                        TIMESTAMP NOT NULL,
    updated_at                        TIMESTAMP NOT NULL,
    submitted_at                      TIMESTAMP,
    reviewed_at                       TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_verification_app_group  ON verification_application (group_id, updated_at);
CREATE INDEX IF NOT EXISTS idx_verification_app_status ON verification_application (status, updated_at);

-- =============================================================
-- 20. Publisher publish audit
-- =============================================================
CREATE TABLE IF NOT EXISTS publisher_publish_audit (
    id                       BIGSERIAL PRIMARY KEY,
    event_type               VARCHAR(40) NOT NULL,
    actor_email              VARCHAR(255),
    publisher_email          VARCHAR(255),
    organization_id          VARCHAR(80),
    organization_name        VARCHAR(255),
    organization_kind        VARCHAR(80),
    reach_label              VARCHAR(400),
    permanent_address        VARCHAR(400),
    temporary_event_address  VARCHAR(400),
    latitude                 DOUBLE PRECISION,
    longitude                DOUBLE PRECISION,
    post_table               VARCHAR(32) NOT NULL,
    post_id                  BIGINT NOT NULL,
    message                  TEXT,
    review_status            VARCHAR(24) NOT NULL,
    reviewer_email           VARCHAR(255),
    reviewer_notes           VARCHAR(1000),
    reviewed_at              TIMESTAMP,
    created_at               TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pub_audit_created ON publisher_publish_audit (created_at);
CREATE INDEX IF NOT EXISTS idx_pub_audit_actor   ON publisher_publish_audit (actor_email);
CREATE INDEX IF NOT EXISTS idx_pub_audit_org     ON publisher_publish_audit (organization_id);
CREATE INDEX IF NOT EXISTS idx_pub_audit_post    ON publisher_publish_audit (post_table, post_id);

-- =============================================================
-- 21. Community moderation reports
-- =============================================================
CREATE TABLE IF NOT EXISTS community_report (
    id                  BIGSERIAL PRIMARY KEY,
    target_type         VARCHAR(16) NOT NULL,
    target_id           BIGINT NOT NULL,
    post_id             BIGINT,
    reporter_email      VARCHAR(255) NOT NULL,
    target_author_email VARCHAR(255),
    reason              VARCHAR(32) NOT NULL,
    details             VARCHAR(1000),
    content_preview     VARCHAR(1000),
    status              VARCHAR(24) NOT NULL,
    reviewer_email      VARCHAR(255),
    reviewer_notes      VARCHAR(1000),
    reviewed_at         TIMESTAMP,
    created_at          TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_community_report_status_created ON community_report (status, created_at);
CREATE INDEX IF NOT EXISTS idx_community_report_target         ON community_report (target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_community_report_post           ON community_report (post_id);
CREATE INDEX IF NOT EXISTS idx_community_report_reporter       ON community_report (reporter_email);

-- =============================================================
-- 22. Resource listings
-- =============================================================
CREATE TABLE IF NOT EXISTS resource_listing (
    id                  BIGSERIAL PRIMARY KEY,
    title               VARCHAR(140) NOT NULL,
    description         VARCHAR(1000),
    category            VARCHAR(40),
    latitude            DOUBLE PRECISION,
    longitude           DOUBLE PRECISION,
    address             VARCHAR(240),
    contact             VARCHAR(400),
    source              VARCHAR(16) NOT NULL,
    status              VARCHAR(16) NOT NULL,
    source_key          VARCHAR(120),
    submitted_by_email  VARCHAR(160),
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP NOT NULL,
    CONSTRAINT uk_resource_listing_source_key UNIQUE (source_key)
);
CREATE INDEX IF NOT EXISTS idx_resource_listing_status ON resource_listing (status);
CREATE INDEX IF NOT EXISTS idx_resource_listing_coords ON resource_listing (latitude, longitude);
