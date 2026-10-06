package org.example.footballmanager.newLogic.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Year;

import org.example.footballmanager.newLogic.model.FinanceLedgerEntry;
import org.example.footballmanager.newLogic.model.FinanceCategory;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.repository.FinanceLedgerEntryRepository;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Which season the ledger is read for, and what happens when the game is not playing one.
 *
 * <p>Both tests here are about one method, {@code FinanceLedgerService.activeSeason}, and both are
 * regressions against a version of it that was wrong in a way no test noticed.
 */
@SpringBootTest
@ActiveProfiles("test")
class FinanceLedgerSeasonTest {

    @Autowired FinanceLedgerService ledgerService;
    @Autowired GameClockRepository clocks;
    @Autowired FinanceLedgerEntryRepository entries;
    @Autowired TeamRepository teams;

    @AfterEach
    void clearClocks() {
        clocks.deleteAll();
    }

    @Test
    @DisplayName("the game is read for the furthest-advanced season, not an arbitrary one")
    void theFurthestAdvancedClockWins() {
        clocks.save(clockAt(1));
        clocks.save(clockAt(3));

        var club = new org.example.footballmanager.newLogic.model.Team();
        club.setName("Season probe " + System.nanoTime());
        teams.save(club);

        // Income in seasons 1 and 3, so which one is read is visible in the figure.
        entries.save(FinanceLedgerEntry.of(club, 1, 1, FinanceCategory.BROADCAST,
                5_000.0, "old season"));
        entries.save(FinanceLedgerEntry.of(club, 3, 1, FinanceCategory.BROADCAST,
                7_000.0, "current season"));

        var figures = ledgerService.summarise(club);

        assertThat(figures.get("seasonYear")).isEqualTo(3);
        assertThat(figures.get("incomeToDate")).isEqualTo(7_000.0);
    }

    @Test
    @DisplayName("with no clock there is no season, and never a calendar year")
    void noClockIsNotTheCalendarYear() {
        clocks.deleteAll();
        var club = new org.example.footballmanager.newLogic.model.Team();
        club.setName("Unstarted " + System.nanoTime());
        teams.save(club);

        var figures = ledgerService.summarise(club);

        // Seasons are counted from 1. There is no calendar year anywhere in this game, so a world
        // with no clock must not be read as season 2026 -- that made income which had really been
        // earned come back zero, and refused the club for it.
        assertThat(figures.get("seasonYear")).isNotEqualTo(Year.now().getValue());
        assertThat(figures.get("incomeToDate")).isEqualTo(0.0);
    }

    private GameClock clockAt(int season) {
        GameClock clock = new GameClock();
        clock.setCurrentSeason(season);
        clock.setCurrentWeek(1);
        return clock;
    }
}
