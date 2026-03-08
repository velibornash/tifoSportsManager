package org.example.footballmanager.service;

import org.example.footballmanager.dto.MatchEventFlatDTO;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.MatchPlayerStats;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.repository.MatchPlayerStatsRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchReportServiceTest {

    @Mock private MatchRepository matchRepository;
    @Mock private MatchDetailService matchDetailService;
    @Mock private MatchPlayerStatsRepository matchPlayerStatsRepository;

    @InjectMocks private MatchReportService matchReportService;

    @Test
    void buildMatchReportIncludesHeadlineGoalsAndKeyMoments() {
        Match match = new Match();
        match.setId(15L);
        match.setHomeTeam(team("OFK Omladinac"));
        match.setAwayTeam(team("RFK Beograd"));
        match.setHomeGoals(2);
        match.setAwayGoals(1);

        Player luka = player(9L, "Luka", match.getHomeTeam());
        Player nikola = player(19L, "Nikola", match.getAwayTeam());

        when(matchRepository.findById(15L)).thenReturn(Optional.of(match));
        when(matchDetailService.getMatchEventsFlat(15L)).thenReturn(List.of(
                goal(18, "OFK Omladinac", "Luka", "Mika", "1-0"),
                shot(31, "OFK Omladinac", true),
                yellow(52, "RFK Beograd", "Petar"),
                goal(67, "RFK Beograd", "Nikola", null, "1-1"),
                goal(81, "OFK Omladinac", "Jovan", null, "2-1"),
                substitution(84, "OFK Omladinac", "Mika", "Stefan")
        ));
        when(matchPlayerStatsRepository.findByMatchId(15L)).thenReturn(List.of(
                stats(match, luka, 86, 1, 1, 90, 1, 0, false),
                stats(match, nikola, 78, 1, 0, 90, 0, 0, false)
        ));

        Map<String, Object> payload = matchReportService.buildMatchReport(15L);
        String headline = String.valueOf(payload.get("headline"));
        String report = String.valueOf(payload.get("report"));
        Map<?, ?> motm = (Map<?, ?>) payload.get("manOfTheMatch");

        assertTrue(headline.contains("OFK Omladinac"));
        assertTrue(report.contains("Luka"));
        assertTrue(report.contains("shots"));
        assertTrue(report.contains("Key moments"));
        assertTrue(report.contains("Substitution"));
        assertEquals("Luka", motm.get("playerName"));
        assertEquals("OFK Omladinac", motm.get("teamName"));
        assertEquals(8.6, motm.get("rating10"));
    }

    private Team team(String name) {
        Team team = new Team();
        team.setName(name);
        return team;
    }

    private Player player(Long id, String name, Team team) {
        Player player = new Player();
        player.setId(id);
        player.setName(name);
        player.setTeam(team);
        return player;
    }

    private MatchPlayerStats stats(Match match, Player player, int rating, int goals, int assists,
                                   int minutes, int interceptions, int saves, boolean cleanSheet) {
        MatchPlayerStats stats = new MatchPlayerStats();
        stats.setMatch(match);
        stats.setPlayer(player);
        stats.setRating(rating);
        stats.setGoals(goals);
        stats.setAssists(assists);
        stats.setMinutesPlayed(minutes);
        stats.setInterceptions(interceptions);
        stats.setSaves(saves);
        stats.setCleanSheet(cleanSheet);
        return stats;
    }

    private MatchEventFlatDTO goal(int minute, String team, String scorer, String assist, String scoreAfterGoal) {
        MatchEventFlatDTO dto = new MatchEventFlatDTO();
        dto.setMatchMinute(minute);
        dto.setEventType("GoalEvent");
        dto.setScoreTeam(team);
        dto.setScorer(scorer);
        dto.setAssistant(assist);
        dto.setScoreAfterGoal(scoreAfterGoal);
        dto.setGoalScored(true);
        return dto;
    }

    private MatchEventFlatDTO shot(int minute, String team, boolean onTarget) {
        MatchEventFlatDTO dto = new MatchEventFlatDTO();
        dto.setMatchMinute(minute);
        dto.setEventType(onTarget ? "ShotOnTargetEvent" : "ShotOffTargetEvent");
        if (onTarget) dto.setShotOnTargetTeam(team);
        else dto.setShotOffTargetTeam(team);
        return dto;
    }

    private MatchEventFlatDTO yellow(int minute, String team, String player) {
        MatchEventFlatDTO dto = new MatchEventFlatDTO();
        dto.setMatchMinute(minute);
        dto.setEventType("YellowCardEvent");
        dto.setYellowCardTeam(team);
        dto.setYellowCardPlayer(player);
        return dto;
    }

    private MatchEventFlatDTO substitution(int minute, String team, String out, String in) {
        MatchEventFlatDTO dto = new MatchEventFlatDTO();
        dto.setMatchMinute(minute);
        dto.setEventType("SubstitutionEvent");
        dto.setSubstitutionTeam(team);
        dto.setPlayerOutName(out);
        dto.setPlayerInName(in);
        return dto;
    }
}