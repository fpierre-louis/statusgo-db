-- Resource category vocabulary — reclassify the Red Cross training listing.
--
-- The board had two vocabularies: the submit sheet's nine chips, and the three
-- values ResourceSeeder wrote (hotline / recovery / medical). ResourceCategory
-- now defines one set of eleven that both writers read, and the service
-- rejects anything outside it. Of the four seeded rows, three already carry
-- canonical values (hotline x2, recovery x1) and need no change.
--
-- The fourth does. "CPR & first-aid classes" was seeded as `medical`, but
-- someone filtering Medical during an emergency is asking where to get CARE,
-- and a class signup is not care. It moves to `other` as an interim placement
-- — it is preparedness education and probably belongs with the guides rather
-- than on this board at all, which is logged as an open question.
--
-- This migration is required rather than optional: ResourceSeeder is
-- INSERT-ONLY (`if (repo.findBySourceKey(...).isPresent()) return;`), so
-- editing the category in Java changes nothing for a row that already exists.
-- Scoped by source_key so it is exact, and idempotent by the WHERE clause.
--
-- Verified read-only against prod before writing: 4 rows total, all seeded,
-- zero user submissions — hotline 2, medical 1, recovery 1.

UPDATE resource_listing
   SET category = 'other'
 WHERE source_key = 'official:redcross-classes'
   AND category = 'medical';
