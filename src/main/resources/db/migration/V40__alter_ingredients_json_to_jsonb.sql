-- V40 — Promote meal_plan_v2.ingredients_json from TEXT to native Postgres JSONB.
--
-- V39 added ingredients_json as a plain TEXT column holding a hand-serialized
-- Jackson JSON string (via a @PrePersist/@PostLoad bridge on MealPlan). That
-- bridge has now been replaced by a native Hibernate 6 mapping
-- (@JdbcTypeCode(SqlTypes.JSON) + columnDefinition = "jsonb"), so the storage
-- type must become real JSONB. JSONB gives us validation on write, canonical
-- storage, and queryable/indexable ingredient data instead of an opaque blob.
--
-- The column is only days old (introduced in V39) and was written exclusively
-- by the Jackson bridge, so every existing value is either NULL or a valid JSON
-- object string — the cast is safe. NULLIF(..,'') defends against any stray
-- empty string (which is not valid JSON and would abort the cast); the bridge
-- wrote NULL, never '', so in practice this only guards the theoretical case.
--
-- Postgres-only DDL. It never runs under the H2 test profile (spring.flyway
-- .enabled=false there; Hibernate builds the H2 schema from the entity via
-- ddl-auto=create-drop, where the same columnDefinition = "jsonb" is emitted
-- and H2's PostgreSQL mode accepts it).

ALTER TABLE meal_plan_v2
    ALTER COLUMN ingredients_json TYPE jsonb
    USING NULLIF(ingredients_json, '')::jsonb;
