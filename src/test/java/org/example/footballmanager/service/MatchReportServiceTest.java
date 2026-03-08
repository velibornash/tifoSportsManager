package org.example.footballmanager.service;

import org.example.footballmanager.dto.MatchEventFlatDTO;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.repository.MatchRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchReportServiceTest {

    @Mock private MatchRepository matchRepository;
    @Mock private MatchDetailService matchDetailService;

    @InjectMocks private MatchReportService matchReportService;

    @Test
    void buildMatchReportIncludesHeadlineGoalsAndKeyMoments() {
        Match match = new Match();
        match.setId(15L);
        match.setHomeTeam(team("OFK Omladinac"));
        match.setAwayTeam(team("RFK Beograd"));
        match.setHomeGoals(2);
        match.setAwayGoals(1);

        when(matchRepository.findById(15L)).thenReturn(Optional.of(match));
        when(matchDetailService.getMatchEventsFlat(15L)).thenReturn(List.of(
                goal(18, "OFK Omladinac", "Luka", "Mika", "1-0"),
                shot(31, "OFK Omladinac", true),
                yellow(52, "RFK Beograd", "Petar"),
                goal(67, "RFK Beograd", "Nikola", null, "1-1"),
                goal(81, "OFK Omladinac", "Jovan", null, "2-1"),
                substitution(84, "OFK Omladinac", "Mika", "Stefan")
        ));

        Map<String, Object> payload = matchReportService.buildMatchReport(15L);
        String headline = String.valueOf(payload.get("headline"));
        String report = String.valueOf(payload.get("report"));

        assertTrue(headline.contains("OFK Omladinac"));
        assertTrue(report.contains("Luka"));
        assertTrue(report.contains("shots"));
        assertTrue(report.contains("Key moments"));
        assertTrue(report.contains("Substitution"));
    }

    private Team team(String name) {
        Team team = new Team();
        team.setName(name);
        return team;
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