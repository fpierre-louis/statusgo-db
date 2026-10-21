-- Starting points saved from the old quick-add chips carry the chip's words as
-- their NAME (owner 2026-10-21, EXEC-20): the chips now say one plain word, so
-- names that are EXACTLY an old chip default become the new word. Anything a
-- household typed itself is theirs and is left alone. Data only; V119 already
-- typed these rows. The FE label used a CURLY apostrophe (U+2019), so both
-- spellings of "Kids’ school" are matched.

UPDATE origin_location
   SET name = 'School'
 WHERE LOWER(TRIM(REPLACE(name, '’', ''''))) = 'kids'' school';

UPDATE origin_location
   SET name = 'Caregiver'
 WHERE LOWER(TRIM(name)) = 'other caregiver';
