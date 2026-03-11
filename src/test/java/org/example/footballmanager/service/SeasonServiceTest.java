package org.example.footballmanager.service;

import org.example.footballmanager.model.*;
import org.example.footballmanager.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeasonServiceTest {

    @Mock private GameClockRepository gameClockRepository;
    @Mock private SeasonRepository seasonRepository;
    @Mock private SeasonCompetitionRepository seasonCompetitionRepository;
    @Mock private CompetitionEntryRepository competitionEntryRepository;
    @Mock private CompetitionRepository competitionRepository;
    @Mock private MatchFixtureRepository matchFixtureRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private PlayerRepository playerRepository;
    @Mock private JuniorRepository juniorRepository;
    @Mock private YouthAcademyService youthAcademyService;

    @InjectMocks private SeasonService seasonService;

    @Test
    void applyPromotionRelegationKeepsSecondPlacedClubUpAndRelegatesBottomTwo() {
        Country srb = new Country();
        srb.setIsoCode("SRB");

        Competition superLiga = league(1L, "Superliga Srbije", srb, 1, 1);
        Competition prvaA = league(2L, "Prva liga A", srb, 2, 1);
        Competition prvaB = league(3L, "Prva liga B", srb, 2, 2);

        Team smederevo = team(101L, "SK Smederevo", superLiga);
        Team omladinac = team(102L, "OFK Omladinac", superLiga);
        Team uzice = team(103L, "TSK Užice City", superLiga);
        Team beograd = team(104L, "TSK Beograd", superLiga);
        Team zrenjanin1919 = team(105L, "OFK Zrenjanin 1919", superLiga);
        Team cacak = team(106L, "FK Čačak United", superLiga);
        Team bor = team(107L, "OFK Bor Sloga", superLiga);
        Team kraljevo = team(108L, "NK Kraljevo Sport", superLiga);
        Team pancevo = team(109L, "SK Pančevo Sport", superLiga);
        Team zrenjanin = team(110L, "FK Zrenjanin", superLiga);

        Team championA = team(201L, "Prva A Champion", prvaA);
        Team runnerA = team(202L, "Prva A Runner", prvaA);
        Team championB = team(203L, "Prva B Champion", prvaB);
        Team runnerB = team(204L, "Prva B Runner", prvaB);

        SeasonCompetition topSc = seasonCompetition(superLiga, 2026);
        SeasonCompetition lowerScA = seasonCompetition(prvaA, 2026);
        SeasonCompetition lowerScB = seasonCompetition(prvaB, 2026);

        when(seasonCompetitionRepository.findByCompetitionAndSeasonYear(superLiga, 2026)).thenReturn(Optional.of(topSc));
        when(seasonCompetitionRepository.findByCompetitionAndSeasonYear(prvaA, 2026)).thenReturn(Optional.of(lowerScA));
        when(seasonCompetitionRepository.findByCompetitionAndSeasonYear(prvaB, 2026)).thenReturn(Optional.of(lowerScB));
        when(competitionRepository.findAll()).thenReturn(List.of(superLiga, prvaA, prvaB));
        when(competitionEntryRepository.findBySeasonCompetition(topSc)).thenReturn(List.of(
                entry(topSc, smederevo, 40, 33, 14),
                entry(topSc, omladinac, 39, 42, 22),
                entry(topSc, uzice, 33, 29, 27),
                entry(topSc, beograd, 26, 30, 34),
                entry(topSc, zrenjanin1919, 24, 23, 23),
                entry(topSc, cacak, 23, 30, 30),
                entry(topSc, bor, 19, 21, 28),
                entry(topSc, kraljevo, 18, 22, 31),
                entry(topSc, pancevo, 16, 24, 31),
                entry(topSc, zrenjanin, 13, 18, 32)
        ));
        when(competitionEntryRepository.findBySeasonCompetition(lowerScA)).thenReturn(List.of(
                entry(lowerScA, championA, 52, 34, 15),
                entry(lowerScA, runnerA, 49, 30, 18)
        ));
        when(competitionEntryRepository.findBySeasonCompetition(lowerScB)).thenReturn(List.of(
                entry(lowerScB, championB, 54, 36, 16),
                entry(lowerScB, runnerB, 47, 28, 20)
        ));

        MatchFixture playoffA = playoffFixture(superLiga, 2026, bor, runnerA, 2, 0);
        MatchFixture playoffB = playoffFixture(superLiga, 2026, kraljevo, runnerB, 1, 0);
        when(matchFixtureRepository.findByCompetitionIdAndSeasonYearAndRoundNumberOrderByMatchDateAsc(1L, 2026, SeasonService.PLAYOFF_WEEK))
                .thenReturn(List.of(playoffA, playoffB));

        seasonService.applyPromotionRelegation(superLiga, 2026);

        assertSame(superLiga, omladinac.getCompetition());
        assertSame(superLiga, bor.getCompetition());
        assertSame(superLiga, kraljevo.getCompetition());
        assertSame(prvaA, pancevo.getCompetition());
        assertSame(prvaB, zrenjanin.getCompetition());
        assertSame(superLiga, championA.getCompetition());
        assertSame(superLiga, championB.getCompetition());
        assertSame(prvaA, runnerA.getCompetition());
        assertSame(prvaB, runnerB.getCompetition());
    }

    @Test
    void performPromotionRelegationAndNewSeasonPreparesAllSerbianLeagues() {
        Country srb = new Country();
        srb.setIsoCode("SRB");

        Competition superLiga = league(1L, "Superliga Srbije", srb, 1, 1);
        Competition prvaA = league(2L, "Prva liga A", srb, 2, 1);
        Competition prvaB = league(3L, "Prva liga B", srb, 2, 2);

        Competition cup = new Competition();
        cup.setId(9L);
        cup.setName("Kup Srbije");
        cup.setCountry(srb);
        cup.setType(CompetitionType.CUP);

        GameClock clock = new GameClock();
        clock.setId(1L);
        clock.setCurrentSeason(1);
        clock.setCurrentWeek(SeasonService.FRIENDLY_WEEK);
        clock.setCurrentDate(LocalDateTime.of(2026, 6, 1, 12, 0));

        SeasonService spyService = spy(seasonService);
        doReturn(2025, 2026).when(spyService).getActiveSeasonYear();
        doReturn(clock).when(spyService).getOrCreateClock();
        doNothing().when(spyService).applyPromotionRelegation(any(), anyInt());
        doNothing().when(spyService).agePlayersAndJuniorsOneYear();
        doReturn(new Season()).when(spyService).ensureActiveSeasonEntity();
        doNothing().when(spyService).ensureEntriesForSeasonCompetition(any(), anyInt());
        doNothing().when(spyService).ensureDoubleRoundRobinSchedule(any(), anyInt());
        doNothing().when(spyService).resetCompetitionEntriesForSeason(any(), anyInt());
        when(competitionRepository.findAll()).thenReturn(List.of(superLiga, prvaA, prvaB, cup));
        when(gameClockRepository.save(clock)).thenReturn(clock);

        spyService.performPromotionRelegationAndNewSeason(superLiga);

        verify(spyService).ensureEntriesForSeasonCompetition(superLiga, 2026);
        verify(spyService).ensureEntriesForSeasonCompetition(prvaA, 2026);
        verify(spyService).ensureEntriesForSeasonCompetition(prvaB, 2026);
        verify(spyService, never()).ensureEntriesForSeasonCompetition(cup, 2026);

        verify(spyService).ensureDoubleRoundRobinSchedule(superLiga, 2026);
        verify(spyService).ensureDoubleRoundRobinSchedule(prvaA, 2026);
        verify(spyService).ensureDoubleRoundRobinSchedule(prvaB, 2026);
        verify(spyService, never()).ensureDoubleRoundRobinSchedule(cup, 2026);

        verify(spyService, times(3)).resetCompetitionEntriesForSeason(any(Competition.class), anyInt());
    }

    @Test
    void buildPlayoffSummaryUsesNextSeasonArchiveToResolveDrawnPlayoffWinner() {
        Country srb = new Country();
        srb.setIsoCode("SRB");

        Competition superLiga = league(1L, "Superliga Srbije", srb, 1, 1);
        Competition prvaA = league(2L, "Prva liga A", srb, 2, 1);
        Competition prvaB = league(3L, "Prva liga B", srb, 2, 2);

        Team[] topTeams = new Team[10];
        for (int i = 0; i < 10; i++) {
            topTeams[i] = team(100L + i, "Top " + (i + 1));
        }
        Team runnerA = team(301L, "Runner A");
        Team runnerB = team(302L, "Runner B");
        Team championA = team(401L, "Champion A");
        Team championB = team(402L, "Champion B");

        SeasonCompetition topSc = seasonCompetition(superLiga, 2026);
        SeasonCompetition lowerScA = seasonCompetition(prvaA, 2026);
        SeasonCompetition lowerScB = seasonCompetition(prvaB, 2026);
        SeasonCompetition nextTopSc = seasonCompetition(superLiga, 2027);

        Match drawnPlayoff = new Match();
        drawnPlayoff.setHomeGoals(1);
        drawnPlayoff.setAwayGoals(1);

        MatchFixture playoffA = new MatchFixture();
        playoffA.setCompetition(superLiga);
        playoffA.setSeasonYear(2026);
        playoffA.setRoundNumber(SeasonService.PLAYOFF_WEEK);
        playoffA.setHomeTeam(topTeams[6]);
        playoffA.setAwayTeam(runnerA);
        playoffA.setPlayedMatch(drawnPlayoff);

        MatchFixture playoffB = new MatchFixture();
        playoffB.setCompetition(superLiga);
        playoffB.setSeasonYear(2026);
        playoffB.setRoundNumber(SeasonService.PLAYOFF_WEEK);
        playoffB.setHomeTeam(topTeams[7]);
        playoffB.setAwayTeam(runnerB);

        when(seasonCompetitionRepository.findByCompetitionAndSeasonYear(superLiga, 2026)).thenReturn(Optional.of(topSc));
        when(seasonCompetitionRepository.findByCompetitionAndSeasonYear(superLiga, 2027)).thenReturn(Optional.of(nextTopSc));
        when(seasonCompetitionRepository.findByCompetitionAndSeasonYear(prvaA, 2026)).thenReturn(Optional.of(lowerScA));
        when(seasonCompetitionRepository.findByCompetitionAndSeasonYear(prvaB, 2026)).thenReturn(Optional.of(lowerScB));
        when(competitionRepository.findAll()).thenReturn(List.of(superLiga, prvaA, prvaB));
        when(competitionEntryRepository.findBySeasonCompetition(topSc)).thenReturn(List.of(
                entry(topSc, topTeams[0], 48), entry(topSc, topTeams[1], 45), entry(topSc, topTeams[2], 42),
                entry(topSc, topTeams[3], 39), entry(topSc, topTeams[4], 36), entry(topSc, topTeams[5], 33),
                entry(topSc, topTeams[6], 30), entry(topSc, topTeams[7], 27), entry(topSc, topTeams[8], 24), entry(topSc, topTeams[9], 21)
        ));
        when(competitionEntryRepository.findBySeasonCompetition(lowerScA)).thenReturn(List.of(
                entry(lowerScA, championA, 55), entry(lowerScA, runnerA, 49)
        ));
        when(competitionEntryRepository.findBySeasonCompetition(lowerScB)).thenReturn(List.of(
                entry(lowerScB, championB, 53), entry(lowerScB, runnerB, 47)
        ));
        when(competitionEntryRepository.findBySeasonCompetition(nextTopSc)).thenReturn(List.of(
                entry(nextTopSc, runnerA, 0),
                entry(nextTopSc, topTeams[7], 0)
        ));
        when(matchFixtureRepository.findByCompetitionIdAndSeasonYearAndRoundNumberOrderByMatchDateAsc(1L, 2026, SeasonService.PLAYOFF_WEEK))
                .thenReturn(List.of(playoffA, playoffB));

        Map<String, Object> summary = seasonService.buildPlayoffSummary(superLiga, 2026);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> playoffResults = (List<Map<String, Object>>) summary.get("playoffResults");
        assertEquals(2, playoffResults.size());
        assertEquals("Runner A", playoffResults.get(0).get("winner"));
        assertEquals("Top 8", playoffResults.get(1).get("winner"));
    }

    private Competition league(Long id, String name, Country country, Integer tier, Integer divisionLevel) {
        Competition competition = new Competition();
        competition.setId(id);
        competition.setName(name);
        competition.setCountry(country);
        competition.setType(CompetitionType.LEAGUE);
        competition.setTier(tier);
        competition.setDivisionLevel(divisionLevel);
        return competition;
    }

    private SeasonCompetition seasonCompetition(Competition competition, int seasonYear) {
        SeasonCompetition sc = new SeasonCompetition();
        sc.setCompetition(competition);
        sc.setSeasonYear(seasonYear);
        return sc;
    }

    private Team team(Long id, String name) {
        Team team = new Team();
        team.setId(id);
        team.setName(name);
        team.setReputation(60.0);
        return team;
    }

    private Team team(Long id, String name, Competition competition) {
        Team team = team(id, name);
        team.setCompetition(competition);
        return team;
    }

    private CompetitionEntry entry(SeasonCompetition sc, Team team, int points) {
        return entry(sc, team, points, points, 0);
    }

    private CompetitionEntry entry(SeasonCompetition sc, Team team, int points, int goalsScored, int goalsConceded) {
        CompetitionEntry entry = new CompetitionEntry();
        entry.setSeasonCompetition(sc);
        entry.setTeam(team);
        entry.setPoints(points);
        entry.setGoalsScored(goalsScored);
        entry.setGoalsConceded(goalsConceded);
        return entry;
    }

    private MatchFixture playoffFixture(Competition competition, int seasonYear, Team home, Team away, int homeGoals, int awayGoals) {
        Match playedMatch = new Match();
        playedMatch.setHomeGoals(homeGoals);
        playedMatch.setAwayGoals(awayGoals);

        MatchFixture fixture = new MatchFixture();
        fixture.setCompetition(competition);
        fixture.setSeasonYear(seasonYear);
        fixture.setRoundNumber(SeasonService.PLAYOFF_WEEK);
        fixture.setHomeTeam(home);
        fixture.setAwayTeam(away);
        fixture.setPlayedMatch(playedMatch);
        return fixture;
    }
}