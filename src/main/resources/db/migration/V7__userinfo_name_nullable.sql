-- Drop nullable=false on names per P3-2 — FE already gracefully degrades
-- via displayName helpers that fall back to the email-local-part. The
-- BE upsertByEmail / upsertByFirebaseUid paths no longer force "User" /
-- "" defaults; nulls flow through. See docs/audit/RACE_AUDIT_GAMEPLAN.md
-- row P3-2 and the entity change in domain/UserInfo.java.

ALTER TABLE user_info ALTER COLUMN user_first_name DROP NOT NULL;
ALTER TABLE user_info ALTER COLUMN user_last_name DROP NOT NULL;
