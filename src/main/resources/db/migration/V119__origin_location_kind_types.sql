-- Starting-point types (owner request 2026-10-21; EXEC-16). A starting point's
-- type is now chosen explicitly (Home, Work, School, Other caregiver, Activity,
-- Other) instead of being derived from its label, so a rename never changes it.
-- `kind` is VARCHAR(16) (V92): 'caregiver' and 'activity' fit, no DDL.
--
-- Data only. Rows saved from the quick-add chips before this carried a kind the
-- old label rule gave them, or none. Each UPDATE parenthesises its OR (AND binds
-- first) and touches only rows whose kind is still empty or 'other'.

-- "Other caregiver" was saved as kind 'other' (or none).
UPDATE origin_location
   SET kind = 'caregiver'
 WHERE (kind = 'other' OR kind IS NULL)
   AND LOWER(TRIM(name)) = 'other caregiver';

-- "Work" and "Kids’ school" chips. The FE label uses a CURLY apostrophe
-- (U+2019), so both spellings are matched.
UPDATE origin_location
   SET kind = 'work'
 WHERE kind IS NULL
   AND LOWER(TRIM(name)) = 'work';

UPDATE origin_location
   SET kind = 'school'
 WHERE kind IS NULL
   AND LOWER(TRIM(REPLACE(name, '’', ''''))) = 'kids'' school';
