-- ============================================================================
-- purge_demo_data.sql  —  Pre-launch demo/test account sanitization
-- Target DB: statusgo-db (Heroku Postgres 16 / prod).  Run:  heroku pg:psql DATABASE_URL -a statusgo-db -f purge_demo_data.sql
--
-- SAFETY MODEL — READ BEFORE RUNNING
--   * This script is a DRY RUN by default: it runs inside ONE transaction that
--     ends in ROLLBACK. Running it as-is CHANGES NOTHING — it only prints the
--     row counts it WOULD delete (psql prints "DELETE n" per statement) and the
--     matched demo accounts, so you can review.
--   * `\set ON_ERROR_STOP on` means any error (e.g. a missed FK child) aborts
--     immediately; because the tx ends in ROLLBACK, nothing is ever committed.
--   * TO ACTUALLY DELETE: review the previews + counts, then change the final
--     `ROLLBACK;` to `COMMIT;` and re-run. That one-word flip is the only switch.
--   * Deletion is keyed by SOFT references (owner_email / household_id / author
--     email strings) because in this schema almost nothing hard-FKs user_info.
--     Order is child-before-parent so the mostly-NO-ACTION FKs never block.
--
-- SCOPE (audited against the live schema, 101 tables):
--   Demo accounts = user_info rows whose email/first/last name contains 'demo'
--   (case-insensitive) or is a known throwaway test email. Confirmed matches:
--     ddione@yopmail.com (Demo Dione), dione@sitprep.com (Dione Demo),
--     kyle@demo.com (Kyle Demo).  >>> EYEBALL the "DEMO ACCOUNTS" preview below <<<
--   We delete: those users; the HOUSEHOLDS THEY OWN and every plan/household row
--   keyed to those emails/households; their authored community/group/ask/DM
--   content and its child rows; their notifications/prefs/follows/etc.; and we
--   SCRUB their emails out of any surviving REAL group's member/admin lists.
--   We do NOT delete real groups/households that merely had a demo member.
-- ============================================================================

\set ON_ERROR_STOP on
\timing off
BEGIN;

-- ---------------------------------------------------------------------------
-- 0.  Materialize the demo set ONCE (consistent, auditable) + hard guard.
-- ---------------------------------------------------------------------------
CREATE TEMP TABLE _demo_email(email text PRIMARY KEY) ON COMMIT DROP;
INSERT INTO _demo_email(email)
SELECT DISTINCT lower(user_email)
FROM user_info
WHERE user_email IS NOT NULL
  AND ( lower(user_email)      LIKE '%demo%'
     OR lower(user_first_name) LIKE '%demo%'
     OR lower(user_last_name)  LIKE '%demo%'
     OR lower(user_email) IN ('test@test.com','admin@example.com','test@example.com','demo@demo.com','test@test.test') )
  -- Defense-in-depth: never let a substring match nuke a known-real account.
  AND lower(user_email) NOT IN ('francisd.plouis@gmail.com','francis.pierre-louis@sunrun.com');

CREATE TEMP TABLE _demo_uid(user_id varchar PRIMARY KEY) ON COMMIT DROP;
INSERT INTO _demo_uid SELECT user_id FROM user_info WHERE lower(user_email) IN (SELECT email FROM _demo_email);

-- Households OWNED by a demo account (their base/personal households).
CREATE TEMP TABLE _demo_hh(group_id varchar PRIMARY KEY) ON COMMIT DROP;
INSERT INTO _demo_hh
SELECT group_id FROM groups
WHERE group_type = 'Household' AND lower(owner_email) IN (SELECT email FROM _demo_email);

\echo '================= DEMO ACCOUNTS FLAGGED (review!) ================='
SELECT u.user_email, u.user_first_name, u.user_last_name, u.user_id
FROM user_info u WHERE lower(u.user_email) IN (SELECT email FROM _demo_email) ORDER BY u.user_email;
\echo '================= DEMO HOUSEHOLDS TO BE DELETED =================='
SELECT g.group_id, g.group_name, g.owner_email FROM groups g WHERE g.group_id IN (SELECT group_id FROM _demo_hh);
\echo '================= BEGIN DELETES (counts = dry-run preview) ========'

-- Reusable predicate note: "owned by a demo user or belonging to a demo
-- household" == (lower(owner_email) IN _demo_email OR household_id IN _demo_hh).

-- ---------------------------------------------------------------------------
-- A.  Demo user's OWN leaf participation (reactions/votes/flags/prefs) —
--     removed everywhere, including on real users' content.
-- ---------------------------------------------------------------------------
DELETE FROM task_reaction               WHERE lower(user_email) IN (SELECT email FROM _demo_email);
DELETE FROM post_reaction               WHERE lower(user_email) IN (SELECT email FROM _demo_email);
DELETE FROM post_comment_reaction       WHERE lower(user_email) IN (SELECT email FROM _demo_email);
DELETE FROM group_post_comment_reaction WHERE lower(user_email) IN (SELECT email FROM _demo_email);
DELETE FROM post_confirm                WHERE lower(user_email) IN (SELECT email FROM _demo_email);
DELETE FROM ask_vote                    WHERE lower(voter_email) IN (SELECT email FROM _demo_email);
DELETE FROM ask_bookmark                WHERE lower(user_email) IN (SELECT email FROM _demo_email);
DELETE FROM group_read_state            WHERE lower(user_email) IN (SELECT email FROM _demo_email);
DELETE FROM group_mute_pref             WHERE lower(user_email) IN (SELECT email FROM _demo_email);
DELETE FROM follow                      WHERE lower(follower_email) IN (SELECT email FROM _demo_email) OR lower(followed_email) IN (SELECT email FROM _demo_email);
DELETE FROM block                       WHERE lower(blocker_email) IN (SELECT email FROM _demo_email) OR lower(blocked_email) IN (SELECT email FROM _demo_email);
DELETE FROM community_report            WHERE lower(reporter_email) IN (SELECT email FROM _demo_email) OR lower(target_author_email) IN (SELECT email FROM _demo_email) OR lower(coalesce(reviewer_email,'')) IN (SELECT email FROM _demo_email);
DELETE FROM notification_log            WHERE lower(coalesce(recipient_email,'')) IN (SELECT email FROM _demo_email) OR actor_user_id IN (SELECT user_id FROM _demo_uid);
DELETE FROM user_alert_preference       WHERE lower(user_email) IN (SELECT email FROM _demo_email);
DELETE FROM idempotency_keys            WHERE lower(coalesce(caller_email,'')) IN (SELECT email FROM _demo_email);
DELETE FROM publisher_publish_audit     WHERE lower(coalesce(actor_email,'')) IN (SELECT email FROM _demo_email) OR lower(coalesce(publisher_email,'')) IN (SELECT email FROM _demo_email) OR lower(coalesce(reviewer_email,'')) IN (SELECT email FROM _demo_email);
DELETE FROM admin_audit_log             WHERE lower(coalesce(actor_email,'')) IN (SELECT email FROM _demo_email);  -- optional: comment out to retain audit trail

-- A2. Platform-admin rows for a demo account (defensive / future-proofing —
--     currently EMPTY in prod). platform_admin_grant CASCADEs from platform_admin,
--     but we clear it explicitly first for clarity and to be dialect-agnostic.
DELETE FROM platform_admin_grant WHERE platform_admin_id IN (SELECT id FROM platform_admin WHERE lower(coalesce(email,'')) IN (SELECT email FROM _demo_email));
DELETE FROM platform_admin       WHERE lower(coalesce(email,'')) IN (SELECT email FROM _demo_email);

-- ---------------------------------------------------------------------------
-- B.  DMs the demo user is part of (dm_message CASCADEs from dm_thread).
-- ---------------------------------------------------------------------------
DELETE FROM dm_thread WHERE lower(participant_a_email) IN (SELECT email FROM _demo_email) OR lower(participant_b_email) IN (SELECT email FROM _demo_email);

-- ---------------------------------------------------------------------------
-- C.  Community feed posts (table `task`) authored by demo OR scoped to a demo
--     household, plus ALL child rows of those posts (incl. real users' replies).
-- ---------------------------------------------------------------------------
CREATE TEMP TABLE _demo_task(id bigint PRIMARY KEY) ON COMMIT DROP;
INSERT INTO _demo_task
SELECT id FROM task
WHERE lower(coalesce(requester_email,'')) IN (SELECT email FROM _demo_email)
   OR coalesce(group_id,'')            IN (SELECT group_id FROM _demo_hh)
   OR coalesce(authored_as_group_id,'') IN (SELECT group_id FROM _demo_hh);
DELETE FROM task_reaction WHERE task_id IN (SELECT id FROM _demo_task);
DELETE FROM task_comment  WHERE task_id IN (SELECT id FROM _demo_task);   -- others' comments on demo posts
DELETE FROM task_comment  WHERE lower(coalesce(author,'')) IN (SELECT email FROM _demo_email);  -- demo's comments on real posts
DELETE FROM task_image_keys WHERE task_id IN (SELECT id FROM _demo_task);
DELETE FROM task_tags       WHERE task_id IN (SELECT id FROM _demo_task);
DELETE FROM task            WHERE id IN (SELECT id FROM _demo_task);

-- ---------------------------------------------------------------------------
-- D.  Group chat posts (table `post`) authored by demo OR in a demo household,
--     plus child rows (mentions/tags/reactions/comments).
-- ---------------------------------------------------------------------------
CREATE TEMP TABLE _demo_post(id bigint PRIMARY KEY) ON COMMIT DROP;
INSERT INTO _demo_post
SELECT id FROM post
WHERE lower(coalesce(author,'')) IN (SELECT email FROM _demo_email)
   OR coalesce(group_id,'')      IN (SELECT group_id FROM _demo_hh);
DELETE FROM post_reactions WHERE post_id IN (SELECT id FROM _demo_post);
DELETE FROM post_mentions  WHERE post_id IN (SELECT id FROM _demo_post);
DELETE FROM post_tags      WHERE post_id IN (SELECT id FROM _demo_post);
DELETE FROM group_post_mentions WHERE group_post_id IN (SELECT id FROM _demo_post);
DELETE FROM group_post_tags     WHERE group_post_id IN (SELECT id FROM _demo_post);
DELETE FROM comment WHERE post_id IN (SELECT id FROM _demo_post);                              -- comments on demo group posts
DELETE FROM comment WHERE lower(coalesce(author,'')) IN (SELECT email FROM _demo_email);       -- demo's comments on real group posts
DELETE FROM chat_message WHERE lower(coalesce(author_email,'')) IN (SELECT email FROM _demo_email) OR coalesce(group_id,'') IN (SELECT group_id FROM _demo_hh);
DELETE FROM post WHERE id IN (SELECT id FROM _demo_post);

-- ---------------------------------------------------------------------------
-- E.  Ask hub content authored by demo + child rows; resource listings.
-- ---------------------------------------------------------------------------
CREATE TEMP TABLE _demo_q(id bigint PRIMARY KEY) ON COMMIT DROP;
INSERT INTO _demo_q SELECT id FROM ask_question WHERE lower(coalesce(author_email,'')) IN (SELECT email FROM _demo_email);
CREATE TEMP TABLE _demo_tip(id bigint PRIMARY KEY) ON COMMIT DROP;
INSERT INTO _demo_tip SELECT id FROM ask_tip WHERE lower(coalesce(author_email,'')) IN (SELECT email FROM _demo_email);
DELETE FROM ask_answer         WHERE lower(coalesce(author_email,'')) IN (SELECT email FROM _demo_email) OR question_id IN (SELECT id FROM _demo_q);
DELETE FROM ask_question_tags    WHERE question_id IN (SELECT id FROM _demo_q);
DELETE FROM ask_question_hazards WHERE question_id IN (SELECT id FROM _demo_q);
DELETE FROM ask_question       WHERE id IN (SELECT id FROM _demo_q);
DELETE FROM ask_tip_tags       WHERE tip_id IN (SELECT id FROM _demo_tip);
DELETE FROM ask_tip_hazards    WHERE tip_id IN (SELECT id FROM _demo_tip);
DELETE FROM ask_tip_image_keys WHERE tip_id IN (SELECT id FROM _demo_tip);
DELETE FROM ask_tip            WHERE id IN (SELECT id FROM _demo_tip);
DELETE FROM resource_listing   WHERE lower(coalesce(submitted_by_email,'')) IN (SELECT email FROM _demo_email);

-- ---------------------------------------------------------------------------
-- F.  Household + plan aggregates keyed by demo email OR demo household.
-- ---------------------------------------------------------------------------
-- Meal plans (v2 chain: meals/ingredients -> meal_plan_v2 -> meal_plan_data_v2)
DELETE FROM meal_plan_meals_v2       WHERE meal_plan_id     IN (SELECT id FROM meal_plan_v2 WHERE meal_plan_data_id IN (SELECT id FROM meal_plan_data_v2 WHERE lower(coalesce(owner_email,'')) IN (SELECT email FROM _demo_email) OR coalesce(household_id,'') IN (SELECT group_id FROM _demo_hh)));
DELETE FROM meal_plan_ingredients_v2 WHERE meal_plan_id     IN (SELECT id FROM meal_plan_v2 WHERE meal_plan_data_id IN (SELECT id FROM meal_plan_data_v2 WHERE lower(coalesce(owner_email,'')) IN (SELECT email FROM _demo_email) OR coalesce(household_id,'') IN (SELECT group_id FROM _demo_hh)));
DELETE FROM meal_plan_v2             WHERE meal_plan_data_id IN (SELECT id FROM meal_plan_data_v2 WHERE lower(coalesce(owner_email,'')) IN (SELECT email FROM _demo_email) OR coalesce(household_id,'') IN (SELECT group_id FROM _demo_hh));
DELETE FROM meal_plan_data_v2        WHERE lower(coalesce(owner_email,'')) IN (SELECT email FROM _demo_email) OR coalesce(household_id,'') IN (SELECT group_id FROM _demo_hh);
-- Meal plans (legacy chain, owner_email only)
DELETE FROM meal_plan_meals       WHERE meal_plan_id     IN (SELECT id FROM meal_plan WHERE meal_plan_data_id IN (SELECT id FROM meal_plan_data WHERE lower(coalesce(owner_email,'')) IN (SELECT email FROM _demo_email)));
DELETE FROM meal_plan_ingredients WHERE meal_plan_id     IN (SELECT id FROM meal_plan WHERE meal_plan_data_id IN (SELECT id FROM meal_plan_data WHERE lower(coalesce(owner_email,'')) IN (SELECT email FROM _demo_email)));
DELETE FROM meal_plan             WHERE meal_plan_data_id IN (SELECT id FROM meal_plan_data WHERE lower(coalesce(owner_email,'')) IN (SELECT email FROM _demo_email));
DELETE FROM meal_plan_data        WHERE lower(coalesce(owner_email,'')) IN (SELECT email FROM _demo_email);
-- Demographic (+ its admin-emails collection)
DELETE FROM demographic_admin_emails WHERE demographic_id IN (SELECT id FROM demographic WHERE lower(coalesce(owner_email,'')) IN (SELECT email FROM _demo_email) OR coalesce(household_id,'') IN (SELECT group_id FROM _demo_hh));
DELETE FROM demographic              WHERE lower(coalesce(owner_email,'')) IN (SELECT email FROM _demo_email) OR coalesce(household_id,'') IN (SELECT group_id FROM _demo_hh);
-- Evacuation
DELETE FROM evacuation_plan WHERE lower(coalesce(owner_email,'')) IN (SELECT email FROM _demo_email) OR coalesce(household_id,'') IN (SELECT group_id FROM _demo_hh);
-- Emergency contacts (contacts -> contact groups)
DELETE FROM emergency_contacts       WHERE group_id IN (SELECT id FROM emergency_contact_groups WHERE lower(coalesce(owner_email,'')) IN (SELECT email FROM _demo_email) OR coalesce(household_id,'') IN (SELECT group_id FROM _demo_hh));
DELETE FROM emergency_contact_groups WHERE lower(coalesce(owner_email,'')) IN (SELECT email FROM _demo_email) OR coalesce(household_id,'') IN (SELECT group_id FROM _demo_hh);
-- Meeting places / origin locations / saved locations
DELETE FROM meeting_place      WHERE lower(coalesce(owner_email,'')) IN (SELECT email FROM _demo_email) OR coalesce(household_id,'') IN (SELECT group_id FROM _demo_hh);
DELETE FROM origin_location    WHERE lower(coalesce(user_email,''))  IN (SELECT email FROM _demo_email) OR coalesce(household_id,'') IN (SELECT group_id FROM _demo_hh);
DELETE FROM user_saved_location WHERE lower(coalesce(owner_email,'')) IN (SELECT email FROM _demo_email);
-- Go bags (go_bag_item CASCADEs from go_bag)
DELETE FROM go_bag WHERE lower(coalesce(owner_email,'')) IN (SELECT email FROM _demo_email) OR coalesce(household_id,'') IN (SELECT group_id FROM _demo_hh);
-- Home stockpile + household-scoped rows
DELETE FROM home_stockpile_item     WHERE coalesce(household_id,'') IN (SELECT group_id FROM _demo_hh);
DELETE FROM household_accompaniment WHERE coalesce(household_id,'') IN (SELECT group_id FROM _demo_hh);
DELETE FROM household_event         WHERE coalesce(household_id,'') IN (SELECT group_id FROM _demo_hh) OR lower(coalesce(actor_email,'')) IN (SELECT email FROM _demo_email);
DELETE FROM household_invite_request WHERE coalesce(household_id,'') IN (SELECT group_id FROM _demo_hh) OR lower(coalesce(candidate_email,'')) IN (SELECT email FROM _demo_email) OR lower(coalesce(requester_email,'')) IN (SELECT email FROM _demo_email) OR lower(coalesce(resolver_email,'')) IN (SELECT email FROM _demo_email);
DELETE FROM household_pet           WHERE coalesce(household_id,'') IN (SELECT group_id FROM _demo_hh);
DELETE FROM household_ritual        WHERE coalesce(household_id,'') IN (SELECT group_id FROM _demo_hh) OR lower(coalesce(opted_in_by_email,'')) IN (SELECT email FROM _demo_email);
DELETE FROM household_manual_member WHERE coalesce(household_id,'') IN (SELECT group_id FROM _demo_hh);

-- ---------------------------------------------------------------------------
-- G.  Plan activations owned by demo + their child collection tables & acks.
-- ---------------------------------------------------------------------------
CREATE TEMP TABLE _demo_act(id varchar PRIMARY KEY) ON COMMIT DROP;
INSERT INTO _demo_act SELECT activation_id FROM plan_activations WHERE lower(coalesce(owner_email,'')) IN (SELECT email FROM _demo_email) OR coalesce(owner_user_id,'') IN (SELECT user_id FROM _demo_uid);
DELETE FROM plan_activation_acks              WHERE activation_id IN (SELECT id FROM _demo_act) OR lower(coalesce(recipient_email,'')) IN (SELECT email FROM _demo_email);
DELETE FROM plan_activation_contact_group_ids WHERE activation_id IN (SELECT id FROM _demo_act);
DELETE FROM plan_activation_contact_ids       WHERE activation_id IN (SELECT id FROM _demo_act);
DELETE FROM plan_activation_household_members WHERE activation_id IN (SELECT id FROM _demo_act);
DELETE FROM plan_activations                  WHERE activation_id IN (SELECT id FROM _demo_act);

-- ---------------------------------------------------------------------------
-- H.  Verification applications by demo (+ CASCADEs its notes) + demo notes.
-- ---------------------------------------------------------------------------
DELETE FROM verification_application_note WHERE lower(coalesce(author_email,'')) IN (SELECT email FROM _demo_email);
DELETE FROM verification_application WHERE lower(coalesce(applicant_email,'')) IN (SELECT email FROM _demo_email) OR lower(coalesce(submitter_email,'')) IN (SELECT email FROM _demo_email) OR lower(coalesce(official_email,'')) IN (SELECT email FROM _demo_email);

-- ---------------------------------------------------------------------------
-- I.  Legacy Rediscover (rs_*) — MUST clear before user_info (they hard-FK it).
-- ---------------------------------------------------------------------------
DELETE FROM rs_event_attendance WHERE lower(coalesce(attendee_email,'')) IN (SELECT email FROM _demo_email);
DELETE FROM rs_event_attendees  WHERE lower(coalesce(attendee_email,'')) IN (SELECT email FROM _demo_email);
DELETE FROM rs_group_membership WHERE lower(coalesce(member_email,'')) IN (SELECT email FROM _demo_email) OR lower(coalesce(invited_by_email,'')) IN (SELECT email FROM _demo_email);
DELETE FROM rs_posts            WHERE lower(coalesce(created_by_email,'')) IN (SELECT email FROM _demo_email);
DELETE FROM rs_events           WHERE lower(coalesce(created_by_email,'')) IN (SELECT email FROM _demo_email) OR lower(coalesce(cancelled_by_email,'')) IN (SELECT email FROM _demo_email);
DELETE FROM rs_groups           WHERE lower(coalesce(owner_email,'')) IN (SELECT email FROM _demo_email);

-- ---------------------------------------------------------------------------
-- J.  Demo households (groups) + non-CASCADE group children.
--     (group_admin/member/pending_emails + sub/parent_group_ids CASCADE.)
-- ---------------------------------------------------------------------------
DELETE FROM group_challenge_progress WHERE group_id IN (SELECT group_id FROM _demo_hh);
DELETE FROM group_jurisdiction_zips  WHERE group_id IN (SELECT group_id FROM _demo_hh);
DELETE FROM group_invites            WHERE group_id IN (SELECT group_id FROM _demo_hh) OR lower(coalesce(issued_by_email,'')) IN (SELECT email FROM _demo_email);
DELETE FROM group_mute_pref          WHERE group_id IN (SELECT group_id FROM _demo_hh);
DELETE FROM group_read_state         WHERE group_id IN (SELECT group_id FROM _demo_hh);
DELETE FROM groups                   WHERE group_id IN (SELECT group_id FROM _demo_hh);

-- ---------------------------------------------------------------------------
-- K.  Scrub demo refs out of SURVIVING real groups / demographics / user lists.
-- ---------------------------------------------------------------------------
DELETE FROM group_member_emails         WHERE lower(member_email) IN (SELECT email FROM _demo_email);
DELETE FROM group_admin_emails          WHERE lower(admin_email)  IN (SELECT email FROM _demo_email);
DELETE FROM group_pending_member_emails WHERE lower(pending_member_email) IN (SELECT email FROM _demo_email);
DELETE FROM demographic_admin_emails    WHERE lower(admin_emails) IN (SELECT email FROM _demo_email);
DELETE FROM user_joined_group_ids       WHERE joined_group_id  IN (SELECT group_id FROM _demo_hh);   -- real users who joined a demo hh
DELETE FROM user_managed_group_ids      WHERE managed_group_id IN (SELECT group_id FROM _demo_hh);

-- ---------------------------------------------------------------------------
-- L.  The demo users themselves — user_* children first (NO ACTION FK), then user_info.
-- ---------------------------------------------------------------------------
DELETE FROM user_info_group_location_sharing WHERE user_info_id IN (SELECT user_id FROM _demo_uid);
DELETE FROM user_joined_group_ids  WHERE user_id IN (SELECT user_id FROM _demo_uid);
DELETE FROM user_managed_group_ids WHERE user_id IN (SELECT user_id FROM _demo_uid);
DELETE FROM user_info WHERE lower(user_email) IN (SELECT email FROM _demo_email);

-- ---------------------------------------------------------------------------
-- M.  Post-condition verification (should all be 0 after a real COMMIT run).
-- ---------------------------------------------------------------------------
\echo '================= VERIFY (expect 0 rows each) ===================='
SELECT 'users_left'      AS check, count(*) FROM user_info WHERE lower(user_email) IN (SELECT email FROM _demo_email)
UNION ALL SELECT 'households_left', count(*) FROM groups WHERE group_id IN (SELECT group_id FROM _demo_hh)
UNION ALL SELECT 'demographic_left', count(*) FROM demographic WHERE lower(coalesce(owner_email,'')) IN (SELECT email FROM _demo_email) OR coalesce(household_id,'') IN (SELECT group_id FROM _demo_hh)
UNION ALL SELECT 'mealplan_left', count(*) FROM meal_plan_data_v2 WHERE lower(coalesce(owner_email,'')) IN (SELECT email FROM _demo_email) OR coalesce(household_id,'') IN (SELECT group_id FROM _demo_hh)
UNION ALL SELECT 'member_refs_left', count(*) FROM group_member_emails WHERE lower(member_email) IN (SELECT email FROM _demo_email);

\echo '*******************************************************************'
\echo '* DRY RUN COMPLETE — nothing was committed (transaction ROLLBACK).*'
\echo '* Review counts above. To APPLY: change final ROLLBACK -> COMMIT.  *'
\echo '*******************************************************************'
ROLLBACK;
-- ^^^ change to COMMIT; to apply for real (after reviewing the dry-run output).
