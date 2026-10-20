package org.example.footballmanager.newLogic.fantasy;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.FantasyTeam;
import org.example.footballmanager.newLogic.model.FantasyTeamScore;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.FantasyPlayerScoreRepository;
import org.example.footballmanager.newLogic.repository.FantasyTeamScoreRepository;
import org.example.footballmanager.newLogic.repository.FantasyTeamRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the League tab reads: the standings and the best players (owner, 2026-10-17).
 *
 * <p><b>Totals are summed from the stored score lines, never recomputed from matches.</b> A manager's
 * points for a week were written down once, with his squad as it stood; adding them up again here would be
 * a second opinion about the same gameweek, and the two would disagree the moment a player's squad changed.
 *
 * <p>The armband is applied here rather than in the score line, because doubling is a fact about the squad
 * and the line records what the <i>player</i> did. Storing a doubled number would make a line mean two
 * different things depending on whose squad it came from.
 */
@Service
@RequiredArgsConstructor
public class FantasyLeagueService {

    private final FantasyTeamRepository fantasyTeams;
    private final FantasyTeamScoreRepository teamScores;
    private final FantasyPlayerScoreRepository playerScores;
    private final CountryRepository countries;

    /** Every entry in one country's league, best first. */
    @Transactional(readOnly = true)
    public Standings standings(Long countryId, int seasonYear) {
        Country country = countries.findById(countryId).orElse(null);
        List<FantasyTeam> teams = fantasyTeams.findByCountryIdAndSeasonYearOrderByIdAsc(countryId, seasonYear);
        if (teams.isEmpty()) {
            return new Standings(country == null ? null : country.getName(), seasonYear, List.of());
        }

        // Summed from the written-down team totals, not from the score lines and not from matches. A
        // gameweek was scored once with the squad as it stood, and adding it up again here would be a
        // second opinion about the same week — and the two would disagree the moment a squad changed.
        List<Long> teamIds = teams.stream().map(FantasyTeam::getId).toList();
        Map<Long, Integer> totals = new HashMap<>();
        Map<Long, Integer> costs = new HashMap<>();
        for (FantasyTeamScore score : teamScores.findByFantasyTeamIdInAndSeasonYear(teamIds, seasonYear)) {
            totals.merge(score.getFantasyTeam().getId(), score.getPoints(), Integer::sum);
            costs.merge(score.getFantasyTeam().getId(), score.getTransferCost(), Integer::sum);
        }

        List<Row> rows = new ArrayList<>(teams.size());
        for (FantasyTeam team : teams) {
            rows.add(new Row(team.getId(), managerName(team),
                    totals.getOrDefault(team.getId(), 0), costs.getOrDefault(team.getId(), 0)));
        }
        rows.sort(Comparator.comparingInt(Row::points).reversed().thenComparing(Row::teamId));
        return new Standings(country == null ? null : country.getName(), seasonYear, rows);
    }

    /**
     * The season's best players in one country.
     *
     * <p>Summed in the database: twelve gameweeks across 7,730 players is ninety thousand rows, and adding
     * them up in a browser is not a read model.
     */
    @Transactional(readOnly = true)
    public List<FantasyPlayerTotal> topPlayers(Long countryId, int seasonYear, int limit) {
        int capped = Math.max(1, Math.min(limit, 200));
        return playerScores.seasonTopScorers(countryId, seasonYear, PageRequest.of(0, capped));
    }

    /** The manager's display name, falling back to his username so a row is never blank. */
    private static String managerName(FantasyTeam team) {
        if (team.getUser() == null) {
            return "—";
        }
        String display = team.getUser().getDisplayName();
        return display == null || display.isBlank() ? team.getUser().getUsername() : display;
    }

    public record Standings(String countryName, int seasonYear, List<Row> standings) {
    }

    /**
     * One manager's season.
     *
     * <p>{@code transferCost} is carried as well as the total, because "why am I behind him" has two very
     * different answers — he picked better players, or he churned his squad — and a leaderboard that cannot
     * tell them apart is a leaderboard nobody trusts.
     */
    public record Row(Long teamId, String manager, int points, int transferCost) {
    }
}
