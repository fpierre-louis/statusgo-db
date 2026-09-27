-- Separate the broad agency stamp from individual operational grants.
-- Existing authorized agencies retain their ordinary operations. Area-alert
-- authority is backfilled only where the approval record (or the direct
-- platform-provisioning publisher record) explicitly enabled emergency posts.

CREATE TABLE IF NOT EXISTS group_agency_capability (
    group_id   VARCHAR(255) NOT NULL REFERENCES groups (group_id) ON DELETE CASCADE,
    capability VARCHAR(48)  NOT NULL,
    PRIMARY KEY (group_id, capability)
);

CREATE INDEX IF NOT EXISTS idx_group_agency_capability_capability
    ON group_agency_capability (capability, group_id);

INSERT INTO group_agency_capability (group_id, capability)
SELECT group_id, 'OPERATE_CIVIC_QUEUE'
FROM groups
WHERE agency_authorized = TRUE
ON CONFLICT DO NOTHING;

INSERT INTO group_agency_capability (group_id, capability)
SELECT group_id, 'MANAGE_WORK'
FROM groups
WHERE agency_authorized = TRUE
ON CONFLICT DO NOTHING;

INSERT INTO group_agency_capability (group_id, capability)
SELECT group_id, 'MANAGE_STAFF'
FROM groups
WHERE agency_authorized = TRUE
ON CONFLICT DO NOTHING;

INSERT INTO group_agency_capability (group_id, capability)
SELECT g.group_id, 'SEND_AREA_ALERTS'
FROM groups g
WHERE g.agency_authorized = TRUE
  AND (
      EXISTS (
          SELECT 1
          FROM verification_application va
          WHERE va.group_id = g.group_id
            AND va.emergency_posting_enabled = TRUE
            AND va.status IN ('APPROVED', 'PROVISIONED')
      )
      OR EXISTS (
          SELECT 1
          FROM user_info ui
          WHERE ui.verified_publisher_group_id = g.group_id
            AND ui.verified_publisher_emergency_posting_enabled = TRUE
      )
      OR EXISTS (
          SELECT 1
          FROM admin_audit_log al
          WHERE al.target_type = 'group'
            AND al.target_id = g.group_id
            AND al.action IN ('CREATED_AGENCY', 'AUTHORIZED_AGENCY')
      )
  )
ON CONFLICT DO NOTHING;
