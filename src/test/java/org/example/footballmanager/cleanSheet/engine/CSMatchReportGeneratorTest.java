package org.example.footballmanager.cleanSheet.engine;

import org.example.footballmanager.cleanSheet.model.CSEventType;
import org.example.footballmanager.cleanSheet.model.CSMatchEvent;
import org.example.footballmanager.cleanSheet.model.CSMatchResult;
import org.example.footballmanager.cleanSheet.model.CSPlayerMatchStats;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CSMatchReportGeneratorTest {

    private final CSMatchReportGenerator generator = new CSMatchReportGenerator();

    @Test
    void detailedReportIncludesRetroSectionsAndStandoutPlayer() {
        CSMatchResult result = CSMatchResult.builder()
                .round(7)
                .homeTeamId(1L)
                .awayTeamId(2L)
                .homeTeamName("FK Omladinac")
                .awayTeamName("Radnicki")
                .homeGoals(2)
                .awayGoals(1)
                .events(List.of(
                        event(1, CSEventType.MATCH_START, "FK Omladinac", null),
                        event(14, CSEventType.SHOT_ON_TARGET, "FK Omladinac", "Luka"),
                        goal(21, "FK Omladinac", "Luka", "Mika", "1:0"),
                        event(44, CSEventType.YELLOW_CARD, "Radnicki", "Petar"),
                        goal(57, "Radnicki", "Jovic", null, "1:1"),
//                        event(72, CSEventType.SUBSTITUTION, "FK Omladinac", null, "Mika", "Stefan"),
                        goal(81, "FK Omladinac", "Luka", null, "2:1"),
                        event(90, CSEventType.MATCH_END, "FK Omladinac", null)
                ))
                .homePlayerStats(List.of(
                        player(9L, "Luka", 8.9, 2, 0),
                        player(10L, "Mika", 7.5, 0, 1)
                ))
                .awayPlayerStats(List.of(player(20L, "Jovic", 7.4, 1, 0)))
                .build();

        String report = generator.buildDetailedReport(result);

        assertTrue(report.contains("MATCH DAY FILE // ROUND 7"));
        assertTrue(report.contains("Final score: FK Omladinac 2:1 Radnicki"));
        assertTrue(report.contains("Man of the match: Luka"));
        assertTrue(report.contains("Desk note:"));
        assertTrue(report.contains("Key incidents"));
        assertTrue(report.contains("Half-time:"));
        assertTrue(report.contains("Second half:"));
        assertTrue(report.contains("Match desk"));
        assertTrue(report.contains("Full-time verdict:"));
    }

    @Test
    void roundReportHighlightsUserMatchAndHeadlineResult() {
        CSMatchResult userMatch = CSMatchResult.builder()
                .round(7)
                .homeTeamId(1L)
                .awayTeamId(2L)
                .homeTeamName("FK Omladinac")
                .awayTeamName("Radnicki")
                .homeGoals(2)
                .awayGoals(1)
                .events(List.of(goal(81, "FK Omladinac", "Luka", null, "2:1")))
                .build();

        CSMatchResult otherMatch = CSMatchResult.builder()
                .round(7)
                .homeTeamId(3L)
                .awayTeamId(4L)
                .homeTeamName("Metalac")
                .awayTeamName("Jedinstvo")
                .homeGoals(3)
                .awayGoals(0)
                .events(List.of(goal(11, "Metalac", "Nikola", null, "1:0")))
                .build();

        String roundReport = generator.buildRoundReport(7, List.of(otherMatch, userMatch), 1L);

        assertTrue(roundReport.contains("ROUND 7 REVIEW"));
        assertTrue(roundReport.contains("FK Omladinac 2:1 Radnicki [YOUR MATCH]"));
        assertTrue(roundReport.contains("Headline result:"));
    }

    private static CSPlayerMatchStats player(Long id, String name, double rating, int goals, int assists) {
        return CSPlayerMatchStats.builder()
                .playerId(id)
                .playerName(name)
                .rating(rating)
                .goals(goals)
                .assists(assists)
                .minutesPlayed(90)
                .build();
    }

    private static CSMatchEvent goal(int minute, String team, String player, String assist, String scoreAfter) {
        return CSMatchEvent.builder()
                .minute(minute)
                .eventType(CSEventType.GOAL)
                .teamName(team)
                .playerName(player)
                .assistName(assist)
                .scoreAfterGoal(scoreAfter)
                .build();
    }

    private static CSMatchEvent event(int minute, CSEventType type, String team, String player) {
        return CSMatchEvent.builder()
                .minute(minute)
                .eventType(type)
                .teamName(team)
                .playerName(player)
                .build();
    }

    private static CSMatchEvent event(int minute, CSEventType type, String team, String playerOut, String playerIn) {
        return CSMatchEvent.builder()
                .minute(minute)
                .eventType(type)
                .teamName(team)
                .playerOutName(playerOut)
                .playerInName(playerIn)
                .build();
    }
}