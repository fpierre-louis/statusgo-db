-- Phase 5 Slice B — agency onboarding "provisioned" handoff (2026-06-15).
--
-- VerificationApplication gains a PROVISIONED terminal status (stored in the
-- existing string `status` column — no enum/CHECK change needed) plus a
-- timestamp for when the agency workspace was handed off. The stamp itself
-- is still granted at APPROVED; PROVISIONED marks onboarding complete.
--
-- Additive + nullable + idempotent.

ALTER TABLE verification_application ADD COLUMN IF NOT EXISTS provisioned_at TIMESTAMP;
