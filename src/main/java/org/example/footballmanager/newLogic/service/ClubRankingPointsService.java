package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.ClubSeasonRankingPoints;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.ClubSeasonRankingPointsRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.RankedMatch;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Replays club matches into the per-season ranking-points ledger ({@code P0-RANK-2}).
 *
 * <p><b>A replay, not an accumulator.</b> {@code ClubRatingService} already rebuilds ratings from
 * scratch by walking every played match in order, which is why the old ratings need no ledger and why a
 * corrupt one repairs itself. This follows the same shape: the ledger is <b>rewritten</b> from the match
 * history every run, so a wrong row is a bug in this class and not a permanent scar in the database.
 *
 * <p><b>The defining property, and the reason this class exists.</b> Points are earned by how a result
 * compared with the forecast, and <b>not at all by who was played</b>. The old Elo weighted every match
 * by the rating gap, so the same 1-0 moved a club twice as far against a strong opponent. Under this
 * system those two matches are worth exactly the same number, which is the owner's own words:
 * *"snaga tima moze da utice na projekciju rezultata ali ne i na rejting poene."*
 *
 * <p><b>Strength still enters — through the forecast.</b> A club's points depend on its squad strength
 * only because strength determines what was predicted, and the forecast is what the result is measured
 * against. Remove the forecast and the dependence disappears, which is why the snapshot is built once
 * per club rather than re-read per match.
 */
@Service
public class ClubRankingPointsService {

    private final MatchRepository matches;
    private final TeamRepository teams;
    private final ClubSeasonRankingPointsRepository ledger;
    private final ScheduleInsightService insights;

    public ClubRankingPointsService(MatchRepository matches, TeamRepository teams,
                                    ClubSeasonRankingPointsRepository ledger,
                                    ScheduleInsightService insights) {
        this.matches = matches;
        this.teams = teams;
        this.ledger = ledger;
        this.insights = insights;
    }

    /**
     * Rewrites every club's per-season subtotals from the played match history.
     *
     * <p>Idempotent by construction: the season's rows are deleted before they are written, so running
     * it twice leaves the same ledger rather than two rows per season for the window to add together.
     *
     * @return what was written, for the log and for a caller that wants to say something about it
     */
    @Transactional
    public Result recompute() {
        List<RankedMatch> played = matches.findPlayedClubRankedInOrder();

        Map<Long, Team> byId = new LinkedHashMap<>();
        for (Team club : teams.findAll()) {
            byId.put(club.getId(), club);
        }
        // One snapshot per club for the whole replay. Built per match instead this is two player reads
        // per match, which on a season of club football is tens of thousands of queries.
        Map<Long, ScheduleInsightService.TeamSnapshot> snapshots =
                insights.buildTeamSnapshots(byId.values());

        // (teamId, season) -> that season's points, kept ordered so the write is deterministic.
        Map<String, Double> subtotals = new TreeMap<>();
        int scored = 0;
        int skipped = 0;

        for (RankedMatch match : played) {
            Team home = byId.get(match.homeTeamId());
            Team away = byId.get(match.awayTeamId());
            if (home == null || away == null || match.seasonYear() == null) {
                // One unusable row must not stop the other fourteen thousand clubs being scored, and a
                // match with no season cannot go in a per-season ledger.
                skipped++;
                continue;
            }

            ScheduleInsightService.FixtureInsights fixture =
                    insights.buildFixtureInsights(home, away, snapshots);
            ScheduleInsightService.Prediction prediction = fixture.prediction();

            double value = RankingPointsEngine.competitionValue(
                    matchTypeFor(match), match.type(), match.scope(), match.teamType(), match.stage());

            double homeExpectedMargin = prediction.expectedHomeGoals() - prediction.expectedAwayGoals();
            double awayExpectedMargin = -homeExpectedMargin;

            accumulate(subtotals, match.homeTeamId(), match.seasonYear(),
                    RankingPointsEngine.pointsFor(homeExpectedMargin, match.homeGoals() - match.awayGoals(),
                            value, RankingPointsEngine.tierWeight(match.homeTierOrDefault())));
            accumulate(subtotals, match.awayTeamId(), match.seasonYear(),
                    RankingPointsEngine.pointsFor(awayExpectedMargin, match.awayGoals() - match.homeGoals(),
                            value, RankingPointsEngine.tierWeight(match.awayTierOrDefault())));
            scored++;
        }

        // Rewrite, not merge.
        ledger.deleteAllInBatch(ledger.findAll());

        int written = 0;
        for (Map.Entry<String, Double> entry : subtotals.entrySet()) {
            String[] parts = entry.getKey().split(":");
            Team club = byId.get(Long.valueOf(parts[0]));
            if (club == null) {
                continue;
            }
            // A zero is written, not skipped. A club that played a season and earned exactly nothing
            // has played a season, and the window treats a missing season as nothing anyway - so the
            // only thing the skip bought was an `==` on a double and a ledger that could not say "this
            // side played and earned nothing". It is also what made the first version of the test
            // vacuous: every subtotal was zero and every assertion compared zero to zero.
            ledger.save(new ClubSeasonRankingPoints(club, Integer.parseInt(parts[1]), entry.getValue()));
            written++;
        }

        return new Result(played.size(), scored, skipped, written);
    }

    /**
     * The match type a fixture row implies.
     *
     * <p>{@code RankedMatch} carries the competition's type but not the match's, and the friendly rule
     * hangs off the match type. Derived here rather than projected so a fixture row cannot disagree with
     * the competition it belongs to; a friendly is a match type, so a competition typed LEAGUE that was
     * actually a friendly is scored as league, which is the safe direction to be wrong in.
     */
    private org.example.footballmanager.newLogic.model.MatchType matchTypeFor(RankedMatch match) {
        return match.type() == org.example.footballmanager.newLogic.model.CompetitionType.CUP
                ? org.example.footballmanager.newLogic.model.MatchType.CUP
                : org.example.footballmanager.newLogic.model.MatchType.LEAGUE;
    }

    private void accumulate(Map<String, Double> subtotals, Long teamId, int season, double points) {
        String key = teamId + ":" + season;
        subtotals.merge(key, points, Double::sum);
    }

    /** What one recompute did, so the log can say something checkable rather than just "done". */
    public record Result(int matchesRead, int matchesScored, int matchesSkipped, int rowsWritten) {
    }
}