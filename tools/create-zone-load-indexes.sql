-- The two indexes PlayerZoneLoad.java declares, as CREATE INDEX CONCURRENTLY.
--
-- Why this file exists when the entity already declares them: `ddl-auto=update` issues a plain
-- CREATE INDEX at every boot, which takes a write lock on the table for the length of the build.
-- CONCURRENTLY builds the index without blocking writes or reads, so an existing database gets
-- these once, by hand.
--
-- The entity stays the source of truth: ddl-auto=update will still create any of these that is
-- missing on a fresh database, and ZoneLoadIndexDeclarationTest asserts the two cannot drift apart.
--
-- Run against sokker_db:
--   psql -U postgres -d sokker_db -f tools/create-zone-load-indexes.sql
--
--   index                query                      what it is for
--   ix_zone_load_player  findByPlayerIdOrderByIdDesc the player's own load history
--   ix_zone_load_match   findByMatchId               every row of one match - a full table scan
--                                                      without it, on a table that is one row per
--                                                      player per zone per match

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_zone_load_match ON player_zone_load (match_id);
