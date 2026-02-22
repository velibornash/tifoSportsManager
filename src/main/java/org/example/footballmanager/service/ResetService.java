package org.example.footballmanager.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.repository.*;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResetService {

@PersistenceContext
private final EntityManager entityManager;

    @Transactional
    public void resetDatabase() {
        log.warn("⚠ RESET DATABASE STARTED – TRUNCATE sa CASCADE");
        entityManager.createNativeQuery("TRUNCATE TABLE promotion_rule, competition_entry, season_competition, match, lineup, lineup_starting_players, lineup_substitutes, player, team, competition, season, country RESTART IDENTITY CASCADE").executeUpdate();
        log.warn("⚠ RESET DATABASE FINISHED – sve obrisano, identiteti resetovani");
    }
}