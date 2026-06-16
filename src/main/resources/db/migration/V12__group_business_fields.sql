-- Phase 5 Slice A — business-only profile fields on the group (2026-06-15).
--
-- The group-creation wizard collects a category + optional website when the
-- creator picks the "Business" group type. These persist as two real
-- additive columns on `groups` (Group.java businessCategory / websiteUrl) —
-- NOT smuggled into `description` (which renders raw on the join page,
-- discover, my-groups, map, and manage surfaces). @RequestBody Group binds
-- them on POST /api/groups and getGroupById ships them back to the FE.
--
-- Additive + nullable + idempotent; safe on prod regardless of partial state.

ALTER TABLE groups ADD COLUMN IF NOT EXISTS business_category VARCHAR(64);
ALTER TABLE groups ADD COLUMN IF NOT EXISTS website_url       VARCHAR(512);
