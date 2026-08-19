package org.example.footballmanager.demo.service.result;

import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.model.PlayerSkills;

import java.util.List;
import java.util.Map;

/**
 * Complete match result — lineups, stats, goals, report.
 */
public record MatchResult(
    String homeTeamName,
    String awayTeamName,
    int homeGoals,
    int awayGoals,
    String finalScore,
    String formation,
    List<LineupPlayer> homeLineup,
    List<LineupPlayer> awayLineup,
    TeamMatchStats homeStats,
    TeamMatchStats awayStats,
    List<PlayerMatchStats> homePlayerStats,
    List<PlayerMatchStats> awayPlayerStats,
    List<GoalDetail> goals,
    MatchReport report,
    long seed
) {
    public record LineupPlayer(
        String id,
        String name,
        String role,
        int number,
        PlayerSkills skills,
        double heightCm
    ) {
        public static LineupPlayer from(Player p, int number) {
            return new LineupPlayer(p.getId(), p.getLabel(), p.getRole(),
                    number, p.getSkills(), p.getHeightCm());
        }
    }
}
