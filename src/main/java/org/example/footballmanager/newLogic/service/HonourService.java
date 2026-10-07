package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.ClubHonour;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.ClubHonourRepository;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Derives and stores the medals a club has won ({@code P2-TROPHY-1}).
 *
 * <p><b>What the owner asked for:</b> on the Club page, in Milestones, a medal in a determined colour
 * (gold / silver / bronze) with which competition and which season beneath it.
 *
 * <p><b>Derived from results, not guessed.</b> Trophies were never stored; they were only derivable from
 * what happened, so the same played fixtures and final tables that already pay ranking points now also
 * decide the medal colour. If there were no honours table, a club could not be told what it won — hence
 * this record.
 *
 * <h2>How the colour is decided (owner's rule)</h2>
 *
 * <ul>
 *   <li><b>League</b> — the final table: position 1 is gold, 2 is silver, 3 is bronze.</li>
 *   <li><b>Cup / international tournament</b> — the winner of the final is gold, the loser of the final is
 *       silver, and the winner of the third-place match is bronze. In a competition without a third-place
 *       match, the bronze medal is simply not awarded.</li>
 * </ul>
 *
 * <p>Recomputed rather than accumulated, so a re-scan cannot grant two golds for the same cup — which is
 * the same bug class that made the bonus pass non-idempotent until bonuses were kept apart.
 */
@Service
public class HonourService {

    private final SeasonCompetitionRepository seasons;
    private final CompetitionEntryRepository entries;
    private final MatchFixtureRepository fixtures;
    private final ClubHonourRepository honours;

    public HonourService(SeasonCompetitionRepository seasons,
                         CompetitionEntryRepository entries,
                         MatchFixtureRepository fixtures,
                         ClubHonourRepository honours) {
        this.seasons = seasons;
        this.entries = entries;
        this.fixtures = fixtures;
        this.honours = honours;
    }

    /**
     * Rewrites every honour derivable from the given season.
     *
     * <p>Idempotent by computing the whole set and replacing rather than merging: calling it twice writes
     * the same rows twice rather than two gold Medal rows for the same club and competition.
     */
    @Transactional
    public int derive(int seasonYear) {
        // Rewrite THIS season, not every season. `deleteAll()` here deleted other seasons' medals and
        // would have emptied the Club page row for historical seasons.
        //
        // **Flush before deleting.** A create-read-write-inside-one-test like the one here inserts a
        // season's medals and then, in the same persistence context, calls deleteBySeasonYear again.
        // The DELETE is sent as SQL immediately; the previous INSERTs are still only pending in the
        // context, so the DELETE matches nothing and the new insert trips the unique constraint. Pushing
        // the pending writes out first is what makes the delete actually delete.
        honours.flush();
        honours.deleteBySeasonYear(seasonYear);
        int written = 0;

        for (SeasonCompetition sc : seasons.findAll()) {
            if (sc.getSeasonYear() == null || sc.getSeasonYear() != seasonYear) continue;
            Competition c = sc.getCompetition();
            if (c == null) continue;

            if (c.getType() == CompetitionType.LEAGUE) {
                written += deriveLeague(sc, c, seasonYear);
            } else {
                written += deriveCup(c, seasonYear);
            }
        }
        return written;
    }

    /** League: the final table decides the colour. 1st gold, 2nd silver, 3rd bronze. */
    private int deriveLeague(SeasonCompetition sc, Competition competition, int seasonYear) {
        List<CompetitionEntry> table = entries.findBySeasonCompetition(sc);
        table.sort(Comparator.comparing(e -> e.getPosition() == null ? Integer.MAX_VALUE : e.getPosition()));

        int written = 0;
        written += award(table, 0, ClubHonour.Medal.GOLD, competition, seasonYear);
        written += award(table, 1, ClubHonour.Medal.SILVER, competition, seasonYear);
        written += award(table, 2, ClubHonour.Medal.BRONZE, competition, seasonYear);
        return written;
    }

    /** Cup / tournament: the final decides gold and silver, the third-place match decides bronze. */
    private int deriveCup(Competition competition, int seasonYear) {
        List<MatchFixture> matches = fixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                competition.getId(), seasonYear);

        Integer maxRound = matches.stream()
                .map(MatchFixture::getRoundNumber)
                .filter(r -> r != null)
                .max(Comparator.naturalOrder()).orElse(null);

        int written = 0;
        Optional<MatchFixture> finalMatch = maxRound == null ? Optional.empty()
                : matches.stream().filter(f -> maxRound.equals(f.getRoundNumber()) && f.isPlayed()).findFirst();
        if (finalMatch.isPresent()) {
            written += crown(finalMatch.get(), competition, seasonYear, ClubHonour.Medal.GOLD, ClubHonour.Medal.SILVER);
        }

        Optional<MatchFixture> thirdPlace = matches.stream()
                .filter(f -> f.getRoundNumber() != null && isThirdPlace(f.getRoundNumber()) && f.isPlayed())
                .findFirst();
        if (thirdPlace.isPresent()) {
            MatchFixture f = thirdPlace.get();
            Team winner = winnerOf(f);
            if (winner != null) {
                honours.save(new ClubHonour(winner, competition.getName(), seasonYear, ClubHonour.Medal.BRONZE));
                written++;
            }
        }
        return written;
    }

    /** An NT-tournament third-place match. NationalTournamentSchedule numbers it round 4. */
    private boolean isThirdPlace(int roundNumber) {
        return roundNumber == org.example.footballmanager.newLogic.model.NationalTournamentSchedule.ROUND_THIRD_PLACE;
    }

    private int award(List<CompetitionEntry> table, int index, ClubHonour.Medal medal,
                      Competition competition, int seasonYear) {
        if (index >= table.size()) return 0;
        CompetitionEntry e = table.get(index);
        if (e.getTeam() == null || e.getPosition() == null || e.getPosition() != index + 1) return 0;
        honours.save(new ClubHonour(e.getTeam(), competition.getName(), seasonYear, medal));
        return 1;
    }

    private int crown(MatchFixture finalMatch, Competition competition, int seasonYear,
                      ClubHonour.Medal gold, ClubHonour.Medal silver) {
        Match m = finalMatch.getPlayedMatch();
        if (m == null) return 0;
        Team winner = m.getHomeGoals() > m.getAwayGoals() ? finalMatch.getHomeTeam()
                : m.getAwayGoals() > m.getHomeGoals() ? finalMatch.getAwayTeam() : null;
        Team loser = m.getHomeGoals() > m.getAwayGoals() ? finalMatch.getAwayTeam()
                : m.getAwayGoals() > m.getHomeGoals() ? finalMatch.getHomeTeam() : null;
        int written = 0;
        if (winner != null) { honours.save(new ClubHonour(winner, competition.getName(), seasonYear, gold)); written++; }
        if (loser != null) { honours.save(new ClubHonour(loser, competition.getName(), seasonYear, silver)); written++; }
        return written;
    }

    private Team winnerOf(MatchFixture f) {
        Match m = f.getPlayedMatch();
        if (m == null) return null;
        return m.getHomeGoals() > m.getAwayGoals() ? f.getHomeTeam()
                : m.getAwayGoals() > m.getHomeGoals() ? f.getAwayTeam() : null;
    }
}
