package org.example.footballmanager.newLogic.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResetService {

    @PersistenceContext
    private final EntityManager entityManager;

    @Transactional
    public void sanitizeLegacyLineupOrderSchema() {
        log.info("Checking lineup schema compatibility for legacy order columns...");
        dropLegacyColumnIfExists("lineup_starting_players", "slot_order");
        dropLegacyColumnIfExists("lineup_substitutes", "bench_order");
    }

    /**
     * Full reset: clears everything and rebuilds from scratch (used by initialize-db).
     * Preserves only the owner account row during truncation.
     * Tactical editor profiles (team_tactics_profile) are handled via
     * snapshot/restore in the calling clearDatabaseOnly().
     */
    @Transactional
    public void resetDatabase() {
        log.warn("RESET DATABASE STARTED - preserving owner account and resetting all sport data");
        entityManager.flush();
        entityManager.clear();
        sanitizeLegacyLineupOrderSchema();
        preserveOwnerAccount();
        List<String> desiredOrder = List.of(
                "community_message",
                "registration_request",
                "training_week_report",
                "team_training_setup",
                "training",
                "team_tactics_profile",
                "match_tick_states",
                "match_player_stats",
                "match_event",
                "lineup_starting_players",
                "lineup_substitutes",
                "lineup",
                "match_fixture",
                "match",
                "promotion_rule",
                "competition_entry",
                "season_competition",
                "junior",
                "player",
                "team",
                "competition",
                "season",
                "country",
                "game_clock",
                "user",
                // Basketball tables (children before parents for TRUNCATE CASCADE safety)
                "bb_match_fixtures",
                "bb_matches",
                "bb_player_season_stats",
                "bb_competition_entries",
                "bb_season_competitions",
                "bb_players",
                "bb_teams",
                "bb_leagues",
                // American Football tables
                "af_match_fixtures",
                "af_matches",
                "af_player_season_stats",
                "af_competition_entries",
                "af_season_competitions",
                "af_players",
                "af_teams"
        );

        truncateTables(desiredOrder);

        // Remove basketball and American Football entries from the shared
        // common_competitions table (now safe — all bb_/af_ tables are empty)
        entityManager.createNativeQuery(
                "DELETE FROM common_competitions WHERE sport IN ('BASKETBALL', 'AMERICAN_FOOTBALL')").executeUpdate();

        List<String> existingNormalized = getExistingTableNames();
        if (existingNormalized.contains("app_user")) {
            entityManager.createNativeQuery("""
                    DELETE FROM app_user
                    WHERE lower(coalesce(username, '')) <> 'velibor@example.com'
                      AND lower(coalesce(email, '')) <> 'velibor@example.com'
                    """).executeUpdate();
        }

        log.warn("RESET DATABASE FINISHED - all sport data cleared, owner account preserved");
    }

    /**
     * Soft reset: clears only match/season/player/team data.
     * Preserves: all users (app_user, user), tactics profiles, countries, game_clock.
     * Also clears basketball and American Football match/player data.
     */
    @Transactional
    public void resetFootballDataOnly() {
        log.warn("SOFT RESET STARTED - preserving users, tactics profiles, countries and game clock");
        sanitizeLegacyLineupOrderSchema();

        List<String> desiredOrder = List.of(
                "community_message",
                "registration_request",
                "training_week_report",
                "team_training_setup",
                "training",
                "match_tick_states",
                "match_player_stats",
                "match_event",
                "lineup_starting_players",
                "lineup_substitutes",
                "lineup",
                "match_fixture",
                "match",
                "promotion_rule",
                "competition_entry",
                "season_competition",
                "junior",
                "player",
                "team",
                "competition",
                "season",
                // Basketball tables
                "bb_match_fixtures",
                "bb_matches",
                "bb_player_season_stats",
                "bb_competition_entries",
                "bb_season_competitions",
                "bb_players",
                "bb_teams",
                "bb_leagues",
                // American Football tables
                "af_match_fixtures",
                "af_matches",
                "af_player_season_stats",
                "af_competition_entries",
                "af_season_competitions",
                "af_players",
                "af_teams"
                // deliberately excluded: team_tactics_profile, app_user, user, country, game_clock
        );

        truncateTables(desiredOrder);

        // Remove basketball and American Football entries from the shared
        // common_competitions table (now safe — all bb_/af_ tables are empty)
        entityManager.createNativeQuery(
                "DELETE FROM common_competitions WHERE sport IN ('BASKETBALL', 'AMERICAN_FOOTBALL')").executeUpdate();

        // Detach all users from their teams (teams are gone after reset)
        List<String> existingNormalized = getExistingTableNames();
        if (existingNormalized.contains("app_user")) {
            entityManager.createNativeQuery("UPDATE app_user SET cteam_id = NULL, tifocteam_id = NULL").executeUpdate();
        }

        entityManager.clear();

        log.warn("SOFT RESET FINISHED - users, tactics profiles and base structure preserved");
    }

    private void preserveOwnerAccount() {
        List<String> existingNormalized = getExistingTableNames();
        if (existingNormalized.contains("app_user")) {
            entityManager.createNativeQuery("""
                    UPDATE app_user
                    SET cteam_id = NULL, tifocteam_id = NULL
                    WHERE lower(coalesce(username, '')) = 'velibor@example.com'
                       OR lower(coalesce(email, '')) = 'velibor@example.com'
                    """).executeUpdate();
        }
    }

    private List<String> getExistingTableNames() {
        @SuppressWarnings("unchecked")
        List<Object> existing = entityManager.createNativeQuery("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                """).getResultList();
        return existing.stream()
                .map(String::valueOf)
                .map(String::toLowerCase)
                .toList();
    }

    private void truncateTables(List<String> desiredOrder) {
        List<String> existingNormalized = getExistingTableNames();
        List<String> toTruncate = new ArrayList<>();
        for (String t : desiredOrder) {
            if (existingNormalized.contains(t.toLowerCase())) {
                if ("match".equals(t) || "user".equals(t)) {
                    toTruncate.add("\"" + t + "\"");
                } else {
                    toTruncate.add(t);
                }
            }
        }
        for (String tableName : toTruncate) {
            entityManager.createNativeQuery("TRUNCATE TABLE " + tableName + " RESTART IDENTITY CASCADE").executeUpdate();
        }
    }

    private void dropLegacyColumnIfExists(String tableName, String columnName) {
        entityManager.createNativeQuery("ALTER TABLE IF EXISTS " + tableName + " DROP COLUMN IF EXISTS " + columnName)
                .executeUpdate();
        log.info("Schema compatibility ensured for {}.{}", tableName, columnName);
    }
}
