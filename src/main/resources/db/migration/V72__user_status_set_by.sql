-- V72 - Who set this status.
--
-- `user_info` carries `user_status`, `status_color` and
-- `user_status_last_updated` and NOTHING about who wrote them. That was fine
-- while the only writer was the person themselves.
--
-- It stops being fine the moment a household admin can mark somebody safe: the
-- roster would render "Checked in 4m ago" about a person nobody has heard
-- from, identical to a real reply. On a life-safety board that is the app
-- stating something it does not know.
--
-- NULL means SELF-REPORTED, which is both the historical truth for every
-- existing row and the right default: a self-report has no proxy to name. The
-- self-status path clears this column on every write, so a person answering for
-- themselves always removes an earlier proxy's attribution rather than leaving
-- it stale under a fresh reply.
--
-- Rows touched at deploy: every existing row gets NULL, which is what they
-- already mean.

ALTER TABLE user_info
    ADD COLUMN IF NOT EXISTS status_set_by_email varchar(255);
