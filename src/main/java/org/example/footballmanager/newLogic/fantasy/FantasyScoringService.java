package org.example.footballmanager.newLogic.fantasy;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.FantasyPick;
import org.example.footballmanager.newLogic.model.FantasyPlayerScore;
import org.example.footballmanager.newLogic.model.FantasyScoreLine;
import org.example.footballmanager.newLogic.model.FantasyTeam;
import org.example.footballmanager.newLogic.model.FantasyTeamScore;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchPlayerStats;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.repository.FantasyPickRepository;
import org.example.footballmanager.newLogic.repository.FantasyPlayerScoreRepository;
import org.example.footballmanager.newLogic.repository.FantasyScoreLineRepository;
import org.example.footballmanager.newLogic.repository.FantasyTeamScoreRepository;
import org.example.footballmanager.newLogic.repository.FantasyTeamRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scores a gameweek, once (owner, 2026-10-17).
 *
 * <p><b>Idempotent by a guard, not by hope.</b> A week that already has rows is skipped, so the job firing
 * twice — after a restart, or because the app was down when the hour passed — cannot double anybody's
 * points. The guard is the existence of the rows themselves rather than a separate flag, because a flag and
 * the rows can disagree and only one of them is the truth.
 *
 * <p><b>Two things are written, and both are needed.</b> {@code FantasyPlayerScore} is what one player's
 * gameweek was worth, for the whole pool: that is what the player-points view reads. {@code
 * FantasyScoreLine} is the snapshot — one row per pick, carrying that week's points and whether he started
 * and wore the armband. Without the snapshot a manager could change his squad after the matches were played
 * and pick the players who had already scored, which is the one way this feature can be cheated.
 *
 * <p><b>Points are read from stored match statistics and never re-simulated.</b> The football happened;
 * this only counts it.
 */
@Service
@RequiredArgsConstructor
public class FantasyScoringService {

    private final FantasyTeamRepository fantasyTeams;
    private final FantasyPickRepository fantasyPicks;
    private final FantasyPlayerScoreRepository playerScores;
    private final FantasyScoreLineRepository scoreLines;
    private final FantasyTeamScoreRepository teamScores;
    private final MatchPlayerStatsRepository matchStats;
    private final MatchFixtureRepository fixtures;

    /**
     * Scores one week for one country.
     *
     * @return what was written, and whether the week had already been scored. Returned rather than logged
     *         so a caller — the job, or a test — can assert it.
     */
    @Transactional
    public ScoreResult scoreWeek(Long countryId, int seasonYear, int weekNumber) {
        // Only entries that were in the game before this week kicked off. A manager who joined into a
        // locked week has a squad but does not score for football he did not pick for.
        List<FantasyTeam> teams =
                fantasyTeams.findByCountryIdAndSeasonYearOrderByIdAsc(countryId, seasonYear).stream()
                        .filter(team -> team.getScoresFromWeek() == null
                                || team.getScoresFromWeek() <= weekNumber)
                        .toList();
        if (alreadyScored(teams, seasonYear, weekNumber)) {
            return new ScoreResult(weekNumber, 0, 0, true);
        }
        Map<Long, PlayerPoints> scored =
                pointsByPlayer(matchStats.findForFantasyWeek(countryId, seasonYear, weekNumber));

        List<FantasyPlayerScore> playerRows = new ArrayList<>(scored.size());
        for (PlayerPoints entry : scored.values()) {
            FantasyPlayerScore row = new FantasyPlayerScore();
            row.setPlayer(entry.player());
            row.setSeasonYear(seasonYear);
            row.setWeekNumber(weekNumber);
            row.setPoints(entry.points());
            playerRows.add(row);
        }
        playerScores.saveAll(playerRows);

        // The snapshot. Copied from the picks as they stand now, which is the squad for this week: a save
        // is refused once the week's first fixture has been prepared, so there is no later squad to confuse
        // it with.
        List<FantasyScoreLine> lines = new ArrayList<>();
        List<FantasyTeamScore> teamRows = new ArrayList<>(teams.size());
        for (FantasyTeam team : teams) {
            int total = 0;
            for (FantasyPick pick : fantasyPicks.findByFantasyTeamId(team.getId())) {
                PlayerPoints points = scored.get(pick.getPlayer().getId());
                int worth = points == null ? 0 : points.points();

                FantasyScoreLine line = new FantasyScoreLine();
                line.setFantasyTeam(team);
                line.setPlayer(pick.getPlayer());
                line.setSeasonYear(seasonYear);
                line.setWeekNumber(weekNumber);
                line.setPoints(worth);
                line.setStarter(pick.isStarter());
                line.setCaptain(pick.isCaptain());
                lines.add(line);

                // The armband is applied here, once, and the total is written down: a later transfer must
                // not be able to reach back and change what a past gameweek was worth.
                if (pick.isStarter()) {
                    total += pick.isCaptain() ? 2 * worth : worth;
                }
            }

            // The transfer cost belongs to the gameweek and not to any player, so it comes off the team
            // total rather than off a line. Charged only for the week it was recorded against — a cost
            // recorded in week 3 must not follow the manager into week 4.
            int cost = team.getTransferCostWeek() != null && team.getTransferCostWeek() == weekNumber
                    ? team.getTransferCost() : 0;

            FantasyTeamScore row = new FantasyTeamScore();
            row.setFantasyTeam(team);
            row.setSeasonYear(seasonYear);
            row.setWeekNumber(weekNumber);
            row.setPoints(total - cost);
            row.setTransferCost(cost);
            teamRows.add(row);
        }
        scoreLines.saveAll(lines);
        teamScores.saveAll(teamRows);

        return new ScoreResult(weekNumber, playerRows.size(), teams.size(), false);
    }

    /**
     * Scores every week that has finished and has not yet been scored, up to and including the given week.
     *
     * <p>What the job calls. <b>A week with fixtures still to play is left alone</b> — not scored as a
     * partial week — because the score is written down once and a total taken from half a matchday would
     * stay wrong for the rest of the season.
     *
     * @param upToWeek the last week worth considering, normally the clock's current week.
     */
    @Transactional
    public List<ScoreResult> scoreFinishedWeeks(Long countryId, int seasonYear, int upToWeek) {
        List<ScoreResult> results = new ArrayList<>();
        for (int week = 1; week <= upToWeek; week++) {
            if (fixtures.countUnplayedInWeek(countryId, seasonYear, week) > 0) {
                continue;
            }
            ScoreResult result = scoreWeek(countryId, seasonYear, week);
            if (!result.alreadyScored()) {
                results.add(result);
            }
        }
        return results;
    }

    /** Whether this week already has rows, in either table. */
    private boolean alreadyScored(List<FantasyTeam> teams, int seasonYear, int weekNumber) {
        if (playerScores.existsBySeasonYearAndWeekNumber(seasonYear, weekNumber)) {
            return true;
        }
        if (teams.isEmpty()) {
            return false;
        }
        List<Long> teamIds = teams.stream().map(FantasyTeam::getId).toList();
        return scoreLines.existsByFantasyTeamIdInAndSeasonYearAndWeekNumber(teamIds, seasonYear, weekNumber);
    }

    /**
     * Every player's points for the week, with the player himself so the row can be written without a
     * second lookup.
     *
     * <p>Bonus is settled per match first, because the top three ratings in a match is a fact about the
     * match and cannot be decided one player at a time.
     */
    private Map<Long, PlayerPoints> pointsByPlayer(List<MatchPlayerStats> stats) {
        Map<Long, List<MatchPlayerStats>> byMatch = new LinkedHashMap<>();
        for (MatchPlayerStats stat : stats) {
            if (stat.getMatch() == null || stat.getPlayer() == null) {
                continue;
            }
            byMatch.computeIfAbsent(stat.getMatch().getId(), key -> new ArrayList<>()).add(stat);
        }

        Map<Long, PlayerPoints> totals = new HashMap<>();
        for (List<MatchPlayerStats> match : byMatch.values()) {
            List<MatchPlayerStats> ranked = new ArrayList<>(match);
            ranked.sort(Comparator.comparingInt(MatchPlayerStats::getRating).reversed()
                    .thenComparing(stat -> stat.getPlayer().getId()));

            Map<Long, Integer> bonus = new HashMap<>();
            for (int rank = 0; rank < ranked.size(); rank++) {
                bonus.put(ranked.get(rank).getPlayer().getId(), FantasyPointsRules.bonusFor(rank));
            }

            for (MatchPlayerStats stat : match) {
                Long playerId = stat.getPlayer().getId();
                int points = FantasyPointsRules.points(performanceOf(stat, bonus.get(playerId)));
                PlayerPoints running = totals.get(playerId);
                // A player can play twice in a week — the league plays two rounds a week — so the week's
                // points are a sum and not a replacement.
                totals.put(playerId, running == null
                        ? new PlayerPoints(stat.getPlayer(), points)
                        : new PlayerPoints(running.player(), running.points() + points));
            }
        }
        return totals;
    }

    /** One stat line as the rules want it, with the side of the scoreline the player was on. */
    private FantasyMatchPerformance performanceOf(MatchPlayerStats stat, Integer bonusPoints) {
        Match match = stat.getMatch();
        boolean home = match.getHomeTeam() != null
                && stat.getPlayer().getTeam() != null
                && match.getHomeTeam().getId().equals(stat.getPlayer().getTeam().getId());
        int conceded = home ? match.getAwayGoals() : match.getHomeGoals();
        return new FantasyMatchPerformance(
                stat.getPositionPlayed() != null ? stat.getPositionPlayed() : stat.getPlayer().getPosition(),
                stat.getMinutesPlayed(),
                stat.getGoals(),
                stat.getAssists(),
                stat.getYellowCards(),
                stat.getRedCards(),
                stat.getSaves(),
                conceded,
                stat.getRating(),
                bonusPoints == null ? 0 : bonusPoints);
    }

    /** A player and what his gameweek was worth. */
    private record PlayerPoints(Player player, int points) {
    }

    /** What one scoring pass wrote. */
    public record ScoreResult(int weekNumber, int playersScored, int teamsScored, boolean alreadyScored) {
    }
}
