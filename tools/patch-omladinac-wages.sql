-- Re-price OFK Omladinac's named squad, 2026-10-11 (owner instruction: "smanji plate direktno u bazi").
--
-- Why: the squad was seeded with a market value and a weekly wage as two independent hand-entered
-- columns, and nothing checked one against the other. The thirteen players came out paying 45-84% of
-- their own market value per year - a goalkeeper valued at 186,900,000 on 1,888,000 a week, which is
-- 98m a year, or more than half his transfer fee every year for ever. The club's wage bill was
-- 18,478,000 a week against income of 176,491.
--
-- What: annual wage becomes 20% of market value, scaled by the same age curve the renewal demand uses
-- (0.72 at 20 and under, 0.86 to 24, 1.00 to 28, 1.12 to 32, 1.05 after), divided by 52.
--
-- The market VALUES are not touched. They are the owner's own figures for his own club; re-pricing
-- them is a different decision and this patch does not make it.
--
-- The same rule now runs in PlayerFactory.createOmladinacPlayers, so a re-seed produces these figures
-- rather than the old ones. This script exists for the world that is already built.
--
-- Idempotent: re-running it recomputes from player_value and changes nothing the second time.
-- Take a backup first (app.backup, or pg_dump from the Postgres 18 tools - the Homebrew pg_dump on
-- PATH is 16 and will refuse a version 18 server).

BEGIN;

UPDATE player p
SET earnings = round(
        (p.player_value * 0.20
         * CASE
             WHEN p.age <= 20 THEN 0.72
             WHEN p.age <= 24 THEN 0.86
             WHEN p.age <= 28 THEN 1.00
             WHEN p.age <= 32 THEN 1.12
             ELSE 1.05
           END / 52.0)::numeric, 2)::double precision
WHERE p.team_id = (SELECT t.id FROM team t WHERE t.name = 'OFK Omladinac' ORDER BY t.id LIMIT 1)
  AND p.player_value > 0
  AND p.earnings <> round(
        (p.player_value * 0.20
         * CASE
             WHEN p.age <= 20 THEN 0.72
             WHEN p.age <= 24 THEN 0.86
             WHEN p.age <= 28 THEN 1.00
             WHEN p.age <= 32 THEN 1.12
             ELSE 1.05
           END / 52.0)::numeric, 2)::double precision;

COMMIT;

-- Check it:
--   SELECT p.name, p.age, p.player_value, p.earnings,
--          round(p.earnings * 52.0 / p.player_value * 100) AS pct_of_value_per_year
--     FROM player p JOIN team t ON t.id = p.team_id
--    WHERE t.name = 'OFK Omladinac' ORDER BY p.rating DESC;
