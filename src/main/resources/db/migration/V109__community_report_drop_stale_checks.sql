-- B6 / FD-01 review fix: group-chat reports could never be saved on prod.
--
-- Prod's community_report carries two CHECK constraints that Hibernate wrote
-- back when the schema ran on ddl-auto=update. V1__baseline.sql has neither,
-- so the drift is invisible from the repo, and ddl-auto=validate never
-- compares CHECKs. Both lag the Java enums:
--   community_report_target_type_check  allows POST, COMMENT only; the enum
--     now also has GROUP_POST, GROUP_COMMENT (group-chat reports, FD-01).
--   community_report_reason_check  lacks MISLEADING_AUTHORITY, PRICE_GOUGING,
--     EMERGENCY_EXPLOITATION, UNLICENSED_EMERGENCY_SERVICE, which the
--     ReportContentSheet already offers.
-- Either way the insert fails, and the request returns 500.
--
-- Dropped, not re-added: the enums (@Enumerated STRING) are the only writers
-- and they validate the value, and a re-added list would drift again the next
-- time a value is added. community_report_status_check matches its enum and
-- stays.
--
-- Read-only count before writing (2026-10-14): community_report has 0 rows.
-- Idempotent (IF EXISTS); plain transactional DDL on an empty table.
-- V109: V107 is reserved by the alerts lane (told), V108 is B2's.

ALTER TABLE community_report DROP CONSTRAINT IF EXISTS community_report_target_type_check;
ALTER TABLE community_report DROP CONSTRAINT IF EXISTS community_report_reason_check;
