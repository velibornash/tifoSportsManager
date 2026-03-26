package org.example.footballmanager.service;

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

    @Transactional
    public void resetDatabase() {
        log.warn("RESET DATABASE STARTED - preserving owner account and resetting football data");
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
                "app_user",
                "user"
        );

        List<String> existing = entityManager.createNativeQuery("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                """).getResultList();

        List<String> existingNormalized = existing.stream()
                .map(String::valueOf)
                .map(String::toLowerCase)
                .toList();

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
            entityManager.createNativeQuery("TRUNCATE TABLE " + tableName + " RESTART IDENTITY").executeUpdate();
        }

        if (existingNormalized.contains("app_user")) {
            entityManager.createNativeQuery("""
                    DELETE FROM app_user
                    WHERE lower(coalesce(username, '')) <> 'velibor@example.com'
                      AND lower(coalesce(email, '')) <> 'velibor@example.com'
                    """).executeUpdate();
        }

        log.warn("RESET DATABASE FINISHED - football data cleared, owner account preserved");
    }

    private void preserveOwnerAccount() {
        List<String> existing = entityManager.createNativeQuery("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                """).getResultList();

        List<String> existingNormalized = existing.stream()
                .map(String::valueOf)
                .map(String::toLowerCase)
                .toList();

        if (existingNormalized.contains("app_user")) {
            entityManager.createNativeQuery("""
                    UPDATE app_user
                    SET team_id = NULL
                    WHERE lower(coalesce(username, '')) = 'velibor@example.com'
                       OR lower(coalesce(email, '')) = 'velibor@example.com'
                    """).executeUpdate();
        }
    }

    private void dropLegacyColumnIfExists(String tableName, String columnName) {
        entityManager.createNativeQuery("ALTER TABLE IF EXISTS " + tableName + " DROP COLUMN IF EXISTS " + columnName)
                .executeUpdate();
        log.info("Schema compatibility ensured for {}.{}", tableName, columnName);
    }
}
