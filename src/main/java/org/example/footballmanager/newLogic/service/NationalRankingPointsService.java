package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountrySeasonRankingPoints;
import org.example.footballmanager.newLogic.model.MatchType;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.CountrySeasonRankingPointsRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.RankedNationalMatch;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Replays national matches into the per-season ranking-points ledger ({@code P0-RANK-3}).
 *
 * <p>The national counterpart of {@link ClubRankingPointsService}, with one difference that is the whole
 * point of keeping them separate: <b>a country is two entries in this system, not one</b>. Its senior side
 * and its U-21 side have separate points, separate seasons of points and a separate place on the ranking,
 * because a country that is excellent at both is not one entity that did well twice.
 *
 * <p>Same three properties as the club replay, and for the same reasons:
 *
 * <ul>
 *   <li><b>A replay, not an accumulator</b> — the ledger is rewritten from match history, so a wrong row
 *       repairs itself instead of being permanent;</li>
 *   <li><b>No term in the opponent's strength</b> — points come from how the result compared with the
 *       forecast, and strength reaches them only through that forecast;</li>
 *   <li><b>One snapshot per side for the whole replay</b>, because a snapshot per match is two player
 *       reads per match and a World Cup qualifying campaign is hundreds of matches.</li>
 * </ul>
 */
@Service
public class NationalRankingPointsService {

    private final MatchRepository matches;
    private final CountryRepository countries;
    private final CountrySeasonRankingPointsRepository ledger;
    private final ScheduleInsightService insights;

    public NationalRankingPointsService(MatchRepository matches, CountryRepository countries,
                                        CountrySeasonRankingPointsRepository ledger,
                                        ScheduleInsightService insights) {
        this.matches = matches;
        this.countries = countries;
        this.ledger = ledger;
        this.insights = insights;
    }

    /**
     * Rewrites every national side's per-season subtotals.
     *
     * @return what was written, for the log and for a caller that wants to say something about it
     */
    @Transactional
    public Result recompute() {
        List<RankedNationalMatch> played = matches.findPlayedNationalRankedInOrder();

        Map<Long, Team> teamById = new LinkedHashMap<>();
        Map<Long, Country> countryByTeamId = new LinkedHashMap<>();
        Map<Long, NationalTeamLevel> levelByTeamId = new LinkedHashMap<>();
        for (Country country : countries.findAll()) {
            claim(teamById, countryByTeamId, levelByTeamId, country, country.getSeniorNationalTeam(),
                    NationalTeamLevel.SENIOR);
            claim(teamById, countryByTeamId, levelByTeamId, country, country.getU21NationalTeam(),
                    NationalTeamLevel.U21);
        }

        Map<Long, ScheduleInsightService.TeamSnapshot> snapshots = insights.buildTeamSnapshots(teamById.values());

        // countryId:level:season -> that season's points, ordered so the write is deterministic.
        Map<String, Double> subtotals = new TreeMap<>();
        int scored = 0;
        int skipped = 0;

        for (RankedNationalMatch match : played) {
            Team home = teamById.get(match.homeTeamId());
            Team away = teamById.get(match.awayTeamId());
            NationalTeamLevel level = match.levelOrDefault();
            if (home == null || away == null || match.seasonYear() == null
                    || !level.equals(levelByTeamId.get(match.homeTeamId()))
                    || !level.equals(levelByTeamId.get(match.awayTeamId()))) {
                // The level guard is the important one, and it earned its place immediately: the first
                // version of the test registered the away side as a country's U-21 and then put it in a
                // senior World Cup fixture. The replay refused to score it, which is the correct answer -
                // scoring it would have added two countries' points into one total. The bug was in the
                // test's fixture, and it is recorded here because a guard that has never rejected
                // anything is a guard nobody has tested.
                skipped++;
                continue;
            }

            ScheduleInsightService.Prediction prediction =
                    insights.buildFixtureInsights(home, away, snapshots).prediction();

            double value = RankingPointsEngine.competitionValue(
                    matchTypeFor(match), match.type(), match.scope(),
                    org.example.footballmanager.newLogic.model.CompetitionTeamType.NATIONAL_TEAM,
                    match.stage());

            // A national side has no division, so the division weight is 1.00 and the totals stay whole
            // unless the competition value itself is fractional.
            double homeExpectedMargin = prediction.expectedHomeGoals() - prediction.expectedAwayGoals();
            double awayExpectedMargin = -homeExpectedMargin;

            Country homeCountry = countryByTeamId.get(match.homeTeamId());
            Country awayCountry = countryByTeamId.get(match.awayTeamId());
            accumulate(subtotals, homeCountry.getId(), level, match.seasonYear(),
                    RankingPointsEngine.pointsFor(homeExpectedMargin, match.homeGoals() - match.awayGoals(),
                            value, RankingPointsEngine.TIER_1));
            accumulate(subtotals, awayCountry.getId(), level, match.seasonYear(),
                    RankingPointsEngine.pointsFor(awayExpectedMargin, match.awayGoals() - match.homeGoals(),
                            value, RankingPointsEngine.TIER_1));
            scored++;
        }

        ledger.deleteAllInBatch(ledger.findAll());

        int written = 0;
        for (Map.Entry<String, Double> entry : subtotals.entrySet()) {
            String[] parts = entry.getKey().split(":");
            Country country = countries.findById(Long.valueOf(parts[0])).orElse(null);
            if (country == null) {
                continue;
            }
            NationalTeamLevel level = NationalTeamLevel.valueOf(parts[1]);
            // Zero is written, not skipped: a side that played a tournament and earned nothing has played
            // one, and the window treats a missing season as nothing either way.
            ledger.save(new CountrySeasonRankingPoints(country, level, Integer.parseInt(parts[2]),
                    entry.getValue()));
            written++;
        }

        return new Result(played.size(), scored, skipped, written);
    }

    /**
     * The match type a national fixture implies.
     *
     * <p>A national-team competition is never a domestic cup, so anything that is not a tournament is
     * treated as an international — which is the correct value for the qualifier and for a friendly.
     */
    private MatchType matchTypeFor(RankedNationalMatch match) {
        return match.type() == CompetitionType.TOURNAMENT ? MatchType.TOURNAMENT : MatchType.INTERNATIONAL;
    }

    private void claim(Map<Long, Team> teamById, Map<Long, Country> countryByTeamId,
                       Map<Long, NationalTeamLevel> levelByTeamId, Country country, Team side,
                       NationalTeamLevel level) {
        if (side == null || side.getId() == null) {
            return;
        }
        teamById.put(side.getId(), side);
        countryByTeamId.put(side.getId(), country);
        levelByTeamId.put(side.getId(), level);
    }

    private void accumulate(Map<String, Double> subtotals, Long countryId, NationalTeamLevel level,
                            int season, double points) {
        subtotals.merge(countryId + ":" + level.name() + ":" + season, points, Double::sum);
    }

    /** What one recompute did, so the log can say something checkable rather than just "done". */
    public record Result(int matchesRead, int matchesScored, int matchesSkipped, int rowsWritten) {
    }
}