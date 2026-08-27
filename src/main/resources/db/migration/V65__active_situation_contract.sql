ALTER TABLE plan_activations
    ADD COLUMN IF NOT EXISTS operational_mode VARCHAR(32),
    ADD COLUMN IF NOT EXISTS movement_directive VARCHAR(64),
    ADD COLUMN IF NOT EXISTS governing_alert_source VARCHAR(64),
    ADD COLUMN IF NOT EXISTS governing_alert_id VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS governing_alert_event VARCHAR(255),
    ADD COLUMN IF NOT EXISTS governing_alert_headline VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS governing_alert_lifecycle_state VARCHAR(32);

ALTER TABLE plan_activations
    ADD CONSTRAINT plan_activations_operational_mode_ck CHECK (
        operational_mode IS NULL OR operational_mode IN (
            'NORMAL',
            'PREPARING',
            'GATHERING',
            'SHELTERING',
            'EVACUATING',
            'RECOVERY'
        )
    ),
    ADD CONSTRAINT plan_activations_movement_directive_ck CHECK (
        movement_directive IS NULL OR movement_directive IN (
            'none',
            'evacuate',
            'shelter_in_place',
            'avoid_area',
            'follow_official_instruction'
        )
    ),
    ADD CONSTRAINT plan_activations_governing_alert_lifecycle_state_ck CHECK (
        governing_alert_lifecycle_state IS NULL
        OR LOWER(governing_alert_lifecycle_state) IN (
            'active',
            'updated',
            'expired',
            'ended',
            'cancelled',
            'canceled',
            'superseded'
        )
    );
