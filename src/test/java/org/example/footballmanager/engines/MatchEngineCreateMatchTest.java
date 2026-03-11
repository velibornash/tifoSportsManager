package org.example.footballmanager.engines;

import org.example.footballmanager.model.*;
import org.example.footballmanager.repository.*;
import org.example.footballmanager.service.AttendanceService;
import org.example.footballmanager.service.PlayerMovementDecisionService;
import org.example.footballmanager.service.SeasonService;
import org.example.footballmanager.service.TacticsAdjustmentService;
import org.example.footballmanager.util.events.EventCreator;
import org.example.footballmanager.util.players.PlayerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchEngineCreateMatchTest {

    @Mock private TacticsAdjustmentService tacticsAdjustmentService;
    @Mock private MatchRepository matchRepository;
    @Mock private MatchFixtureRepository matchFixtureRepository;
    @Mock private MatchEventRepository matchEventRepository;
    @Mock private MatchPlaybackEngine matchPlaybackEngine;
    @Mock private PlayerMovementDecisionService movementService;
    @Mock private GameClockRepository gameClockRepository;
    @Mock private CompetitionRepository competitionRepository;
    @Mock private SeasonCompetitionRepository seasonCompetitionRepository;
    @Mock private CompetitionEntryRepository competitionEntryRepository;
    @Mock private PlayerFactory playerFactory;
    @Mock private PlayerRepository playerRepository;
    @Mock private LineupRepository lineupRepository;
    @Mock private EventCreator eventCreator;
    @Mock private MatchStatisticEngine matchStatisticEngine;
    @Mock private SeasonService seasonService;
    @Mock private AttendanceService attendanceService;

    @InjectMocks private MatchEngine matchEngine;

    @Test
    void createMatchKeepsUsersActiveLeagueForFriendlyWeek() {
        Competition superLiga = league(1L, "SuperLiga", 1);
        Competition prvaLiga = league(2L, "Prva Liga", 2);
        Team userTeam = team(10L, "OFK Omladinac", prvaLiga);
        Team opponent = team(11L, "Rival", prvaLiga);
        SeasonCompetition sc = new SeasonCompetition();
        sc.setCompetition(prvaLiga);
        sc.setSeasonYear(2026);

        GameClock clock = new GameClock();
        clock.setCurrentDate(LocalDateTime.of(2026, 6, 20, 15, 0));

        MatchFixture fixture = new MatchFixture();
        fixture.setHomeTeam(userTeam);
        fixture.setAwayTeam(opponent);
        fixture.setCompetition(prvaLiga);
        fixture.setSeasonYear(2026);
        fixture.setRoundNumber(SeasonService.FRIENDLY_WEEK);
        fixture.setWeekNumber(SeasonService.FRIENDLY_WEEK);
        fixture.setMatchDate(clock.getCurrentDate());

        Map<Long, Player> playersById = new HashMap<>();
        List<Player> homePlayers = squad(userTeam, 100L, playersById);
        List<Player> awayPlayers = squad(opponent, 200L, playersById);

        when(seasonService.getOrCreateClock()).thenReturn(clock);
        when(seasonService.getActiveSeasonYear()).thenReturn(2026);
        when(seasonService.getCurrentWeek()).thenReturn(SeasonService.FRIENDLY_WEEK);
        when(competitionRepository.findById(1L)).thenReturn(Optional.of(superLiga));
        when(seasonService.ensureSeasonCompetition(prvaLiga, 2026)).thenReturn(sc);
        when(competitionEntryRepository.findBySeasonCompetition(sc)).thenReturn(List.of(entry(sc, userTeam), entry(sc, opponent)));
        when(matchFixtureRepository.findByCompetitionIdAndSeasonYearAndRoundNumberAndPlayedFalseOrderByMatchDateAsc(2L, 2026, SeasonService.FRIENDLY_WEEK))
                .thenReturn(List.of(fixture));
        when(playerRepository.findByTeam(userTeam)).thenReturn(homePlayers);
        when(playerRepository.findByTeam(opponent)).thenReturn(awayPlayers);
        when(playerRepository.getReferenceById(anyLong())).thenAnswer(inv -> playersById.get(inv.getArgument(0)));
        when(lineupRepository.findFirstByTeamIdAndMatchIsNullOrderByIdDesc(anyLong())).thenReturn(Optional.empty());
        when(lineupRepository.save(any(Lineup.class))).thenAnswer(inv -> inv.getArgument(0));
        when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));

        Match created = matchEngine.createMatch(userTeam);

        assertSame(prvaLiga, created.getCompetition());
        verify(seasonService).ensureFriendlyWeekFixtures(prvaLiga, 2026);
    }

    private Competition league(Long id, String name, Integer tier) {
        Competition competition = new Competition();
        competition.setId(id);
        competition.setName(name);
        competition.setTier(tier);
        return competition;
    }

    private Team team(Long id, String name, Competition competition) {
        Team team = new Team();
        team.setId(id);
        team.setName(name);
        team.setCompetition(competition);
        return team;
    }

    private CompetitionEntry entry(SeasonCompetition sc, Team team) {
        CompetitionEntry entry = new CompetitionEntry();
        entry.setSeasonCompetition(sc);
        entry.setTeam(team);
        return entry;
    }

    private List<Player> squad(Team team, long baseId, Map<Long, Player> playersById) {
        List<Position> positions = List.of(Position.GK, Position.DEF, Position.DEF, Position.DEF, Position.DEF,
                Position.MID, Position.MID, Position.MID, Position.WNG, Position.WNG, Position.ATT);
        return positions.stream().map(position -> {
            Player player = new Player();
            player.setId(baseId + playersById.size() + 1);
            player.setName(team.getName() + "-" + position + "-" + player.getId());
            player.setTeam(team);
            player.setPosition(position);
            player.setInjured(false);
            playersById.put(player.getId(), player);
            return player;
        }).toList();
    }
}