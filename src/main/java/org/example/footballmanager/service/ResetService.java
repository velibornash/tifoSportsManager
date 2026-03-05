package org.example.footballmanager.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResetService {

    @PersistenceContext
    private final EntityManager entityManager;

    @Transactional
    public void resetDatabase() {
        log.warn("RESET DATABASE STARTED - full truncate with identity reset");
        List<String> desiredOrder = List.of(
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
                "player",
                "team",
                "competition",
                "season",
                "country",
                "game_clock",
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

        if (!toTruncate.isEmpty()) {
            String sql = "TRUNCATE TABLE " + toTruncate.stream().collect(Collectors.joining(", "))
                    + " RESTART IDENTITY CASCADE";
            entityManager.createNativeQuery(sql).executeUpdate();
        }
        log.warn("RESET DATABASE FINISHED - all core tables cleared");
    }
}
