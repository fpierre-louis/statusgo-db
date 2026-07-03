-- Persist action payloads that already ride FCM data frames.
-- Generic group pending-member approvals need the pending requester's email
-- after the notification has been written to the inbox.
ALTER TABLE notification_log
    ADD COLUMN IF NOT EXISTS additional_data TEXT;
