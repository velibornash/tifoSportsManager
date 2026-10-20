-- The four indexes Match.java declares, as CREATE INDEX CONCURRENTLY.
--
-- Why this file exists when the entity already declares them: `ddl-auto=update` issues a plain
-- CREATE INDEX at every boot, which takes a write lock on the table for the length of the build.
-- On the dev database (155 matches) that is instant. On a full-scale one it is 89,280 matches and
-- growing, and a boot that blocks on it is a boot nobody can interrupt. CONCURRENTLY builds the
-- index without blocking writes or reads, so an existing database gets these once, by hand.
--
-- The entity stays the source of truth: ddl-auto=update will still create any of these that is
-- missing on a fresh database, and MatchIndexDeclarationTest asserts the two cannot drift apart.
--
-- Run against sokker_db:
--   psql -U postgres -d sokker_db -f tools/create-match-indexes.sql
--
-- Measured on a full-scale season (89,280 matches, one row per match per week for twelve weeks):
--
-- P1-3 added a fifth, after P1-1 measured and rejected it. See the note on ix_match_date_id in Match.java:
-- on its own it bought nothing, and keyset paging is what made it worth having.
--
--   index                            query                                    before     after
--   ix_match_competition_season      findByCompetitionIdAndSeasonYear        158.7 ms   0.19 ms
--   ix_match_season_week             findBySeasonYearAndWeekNumber           156.4 ms  27.8 ms
--   ix_match_home_team_date          findByHomeTeamIdOrAwayTeamId...         170.3 ms   0.26 ms
--   ix_match_away_team_date          the away half of the same OR            (above)    (above)
--
-- Cost: +16.8 microseconds per match row inserted (25.7 -> 42.5 us/row, min of three 20,000-row
-- inserts into identical clones). At 7,440 matches a matchday that is +125 ms once a matchday, and
-- 24 MB of index per 89,280 matches. Write it once; the entity does the rest.

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_match_competition_season ON match (competition_id, season_year);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_match_season_week        ON match (season_year, week_number);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_match_home_team_date     ON match (home_team_id, match_date);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_match_away_team_date     ON match (away_team_id, match_date);
-- The recovery window's keyset. Column order is the paging order: match_date then id.
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_match_date_id            ON match (match_date, id);
