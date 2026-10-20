package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.repository.ClubSeasonRankingPointsRepository;
import org.example.footballmanager.newLogic.repository.CountrySeasonRankingPointsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a team's or a country's displayed ranking total out of the per-season ledger.
 *
 * <p><b>The one place a ranking number is produced</b> (P0-RANK-6). The ledger is written by
 * {@link ClubRankingPointsService} and {@link NationalRankingPointsService}; this turns it back into the
 * number the owner sees, and it does that in exactly one place so the World page, a country's page and
 * the league table cannot disagree about a total — which is the reason the old endpoint computed
 * {@code position} from a count of countries strictly above rather than storing it.
 *
 * <p>Before this existed, {@code GET /countries/ranking} ordered by
 * {@link RatingEngine}'s national Elo, which was <b>head-to-head</b>: it scaled every result by the rating
 * gap. The owner replaced that with one system, so the ranking is now ordered by achievement points and
 * the Elo is not shown as a ranking at all — a clean cut, with no transition showing both.
 */
@Service
public class RankingPointsReader {

    private final ClubSeasonRankingPointsRepository clubs;
    private final CountrySeasonRankingPointsRepository countries;

    public RankingPointsReader(ClubSeasonRankingPointsRepository clubs,
                               CountrySeasonRankingPointsRepository countries) {
        this.clubs = clubs;
        this.countries = countries;
    }

    /** A club's displayed total, windowed over the seasons in range. */
    @Transactional(readOnly = true)
    public double totalForClub(Long clubId, int currentSeason) {
        Map<Integer, Double> bySeason = new LinkedHashMap<>();
        for (var row : clubs.findByTeamId(clubId)) {
            // points + bonusPoints: the season is worth both.
            bySeason.put(row.getSeasonYear(), row.total());
        }
        return RankingPointsEngine.windowedTotal(currentSeason, bySeason);
    }

    /**
     * Every club's displayed total, in one read.
     *
     * <p><b>Why this exists.</b> The World page asks for the clubs of every country - about fifteen
     * thousand of them - and it used to read each one's total with its own query, plus a second query
     * to ask whether it had ever been rated. That is thirty thousand queries for one page, and the
     * page never came back: clicking "World" hung (owner, 2026-10-20: "klik na World trenutno ne
     * otvara World ni desktop ni mob view").
     *
     * <p>So the ledger is read once, grouped by club here, and the windowing is applied to each
     * club's own seasons - the same {@link RankingPointsEngine#windowedTotal} the single-club read
     * uses, so a club's number cannot differ between the two paths.
     *
     * @param currentSeason the season the window is measured from
     * @return every club that has a ledger row, and its total
     */
    @Transactional(readOnly = true)
    public Map<Long, Double> totalsForClubs(int currentSeason) {
        Map<Long, Map<Integer, Double>> byClub = new LinkedHashMap<>();
        for (var row : clubs.findAll()) {
            Long clubId = row.getTeam() == null ? null : row.getTeam().getId();
            if (clubId == null) continue;
            byClub.computeIfAbsent(clubId, id -> new LinkedHashMap<>())
                    .put(row.getSeasonYear(), row.total());
        }
        Map<Long, Double> totals = new LinkedHashMap<>();
        byClub.forEach((clubId, bySeason) ->
                totals.put(clubId, RankingPointsEngine.windowedTotal(currentSeason, bySeason)));
        return totals;
    }

    /**
     * A country's displayed total at one level.
     *
     * <p>Senior and U-21 are separate reads by construction, so they cannot be pooled even by accident —
     * there is no overload that omits the level.
     */
    @Transactional(readOnly = true)
    public double totalForCountry(Long countryId, NationalTeamLevel level, int currentSeason) {
        Map<Integer, Double> bySeason = new LinkedHashMap<>();
        for (var row : countries.findByCountryIdAndLevel(countryId, level)) {
            bySeason.put(row.getSeasonYear(), row.total());
        }
        return RankingPointsEngine.windowedTotal(currentSeason, bySeason);
    }

    /**
     * Every country's total at one level, in one read per level rather than per country.
     *
     * <p>The old ranking endpoint asked the database for every played national match <b>once per
     * country</b> to decide whether that country had any results — 48 full scans to answer a yes/no
     * question about 48 countries. Now the ledger says which countries have rows, and it says so in one
     * query.
     *
     * @return country id to its windowed total
     */
    @Transactional(readOnly = true)
    public Map<Long, Double> totalsForAllCountries(NationalTeamLevel level, int currentSeason) {
        Map<Long, Double> totals = new LinkedHashMap<>();
        List<org.example.footballmanager.newLogic.model.CountrySeasonRankingPoints> rows =
                countries.findAllByLevel(level);
        Map<Long, Map<Integer, Double>> bySeason = new LinkedHashMap<>();
        for (var row : rows) {
            bySeason.computeIfAbsent(row.getCountry().getId(), key -> new LinkedHashMap<>())
                    .put(row.getSeasonYear(), row.total());
        }
        for (Map.Entry<Long, Map<Integer, Double>> entry : bySeason.entrySet()) {
            totals.put(entry.getKey(), RankingPointsEngine.windowedTotal(currentSeason, entry.getValue()));
        }
        return totals;
    }

    /**
     * Whether a club has any ledger row at all, i.e. has earned anything.
     *
     * <p>Its total is {@link RankingPointsEngine#START_POINTS} either way — a club that has not
     * played sits on the start points, exactly like a club that has played and lost nothing. The
     * number alone cannot tell them apart, so the screen has to be told which is which.
     */
    @Transactional(readOnly = true)
    public boolean hasRankedClub(Long clubId) {
        return clubId != null && clubs.existsByTeamId(clubId);
    }

    /** Whether a country has any ledger row at this level, i.e. has earned anything at all. */
    @Transactional(readOnly = true)
    public boolean hasRanked(Long countryId, NationalTeamLevel level) {
        return countries.existsByCountryIdAndLevel(countryId, level);
    }
}