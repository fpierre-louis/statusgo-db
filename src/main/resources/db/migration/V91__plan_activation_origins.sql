-- The starting point(s) a deployment chose (open-items plan 2.2, 2026-10-02).
--
-- A deployment recorded one meeting place and one shelter but never where the
-- household is starting FROM — Home, Work, Kids' school, as entered in the
-- plan — so the deployed map could only guess (the first origin found). Same
-- shape as the other element collections on plan_activations; rows go with
-- their activation when the expiry sweep deletes it (JPA lifecycle).
CREATE TABLE IF NOT EXISTS plan_activation_origin_location_ids (
    activation_id       VARCHAR(255) NOT NULL,
    origin_location_id  BIGINT
);
CREATE INDEX IF NOT EXISTS idx_plan_activation_origin_location_ids_activation
    ON plan_activation_origin_location_ids (activation_id);
