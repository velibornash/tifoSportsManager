package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.ClubSeasonRankingPoints;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountrySeasonRankingPoints;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.NationalStage;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.model.NationalTournamentSchedule;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.ClubSeasonRankingPointsRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.CountrySeasonRankingPointsRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The one-off achievement bonuses: qualification, every further phase, and every trophy
 * ({@code P0-RANK-5}).
 *
 * <p>The owner: *"plasman na WC donosi svim NT ekipama odredjen broj bonus poena... svaka naredna faza
 * donosi odredjen broj poena. Svaki trofej ukljucujuci i nacionalni kup donosi odredjen broj poena."*
 *
 * <p><b>Read from the fixtures, never from the draw's own record.</b> A team is in the round of sixteen
 * if it appears in one, and that cannot disagree with the draw that made it — the same reasoning
 * {@code NationalRatingService.tournamentQualifiers} already uses.
 *
 * <p><b>Added to the season's existing subtotal, not replacing it.</b> A club that won a game and won
 * the cup has earned both, and the ledger holds one row per club per season precisely so they can be
 * added rather than raced against each other.
 *
 * <p>Applied once and idempotently, so pressing the button twice does not pay two trophies' bonuses.
 */
@Service
public class AchievementBonusService {

    private final CompetitionRepository competitions;
    private final MatchFixtureRepository fixtures;
    private final TeamRepository teams;
    private final CountryRepository countries;
    private final ClubSeasonRankingPointsRepository clubLedger;
    private final CountrySeasonRankingPointsRepository countryLedger;

    public AchievementBonusService(CompetitionRepository competitions, MatchFixtureRepository fixtures,
                                   TeamRepository teams, CountryRepository countries,
                                   ClubSeasonRankingPointsRepository clubLedger,
                                   CountrySeasonRankingPointsRepository countryLedger) {
        this.competitions = competitions;
        this.fixtures = fixtures;
        this.teams = teams;
        this.countries = countries;
        this.clubLedger = clubLedger;
        this.countryLedger = countryLedger;
    }

    /**
     * Adds every achievement bonus for a season to that season's subtotals.
     *
     * @return how many rows gained points, so the log says something checkable
     */
    @Transactional
    public Result apply(int season) {
        int nationalRows = applyNationalTournaments(season);
        int clubRows = applyClubAchievements(season);
        return new Result(nationalRows, clubRows);
    }

    /**
     * National tournaments: qualification, the phase reached, and the trophy.
     *
     * <p>Senior and U-21 are read from separate competitions and written to separate rows, so a nation
     * that reached a World Cup with both sides earns both and neither is credited to the other.
     */
    private int applyNationalTournaments(int season) {
        int touched = 0;
        Map<Long, Country> countryBySide = countryBySideId();
        for (Competition tournament : competitions.findAll()) {
            if (tournament.getType() != CompetitionType.TOURNAMENT
                    || tournament.getNationalStage() != NationalStage.WORLD_CUP) {
                continue;
            }
            NationalTeamLevel level = tournament.getNationalLevel() == null
                    ? NationalTeamLevel.SENIOR
                    : tournament.getNationalLevel();

            Map<Long, Integer> furthestRound = furthestRoundPerTeam(tournament, season);
            if (furthestRound.isEmpty()) {
                continue;
            }
            Long champion = tournamentChampion(tournament, season);

            for (Map.Entry<Long, Integer> entry : furthestRound.entrySet()) {
                Country country = countryBySide.get(entry.getKey());
                if (country == null) {
                    continue;
                }
                // The trophy belongs to the winner of the LAST round only.
                //
                // This collected the winner of every round into one set, so a side that won the round of
                // sixteen, the quarter and the semi and then lost the final was treated as champion and
                // paid the winner's bonus - 210 instead of 165. Winning a round is not winning a
                // tournament, and the two were the same variable.
                double bonus = RankingPointsEngine.tournamentPhaseBonus(
                        entry.getValue(), entry.getKey().equals(champion),
                        RankingPointsEngine.WC_QUALIFIED, RankingPointsEngine.WC_GROUP_STAGE,
                        RankingPointsEngine.WC_ROUND_OF_SIXTEEN, RankingPointsEngine.WC_QUARTER_FINAL,
                        RankingPointsEngine.WC_SEMI_FINAL, RankingPointsEngine.WC_FINAL,
                        RankingPointsEngine.WC_WINNER);
                addTo(countryLedger, country, level, season, bonus);
                touched++;
            }
        }
        return touched;
    }

    /**
     * Club trophies: the national cup, and the international club cup a club reached and how far.
     *
     * <p>Tier-weighted, so a bottom-division title is not worth a top-flight one.
     */
    private int applyClubAchievements(int season) {
        int touched = 0;
        for (Competition competition : competitions.findAll()) {
            boolean domesticCup = competition.getType() == CompetitionType.CUP;
            boolean internationalCup = competition.getScope() != null
                    && "INTERNATIONAL".equals(competition.getScope().name())
                    && competition.getType() == CompetitionType.INTERNATIONAL;
            if (!domesticCup && !internationalCup) {
                continue;
            }

            Map<Long, Integer> furthestRound = furthestRoundPerTeam(competition, season);
            if (furthestRound.isEmpty()) {
                continue;
            }
            Long champion = tournamentChampion(competition, season);

            for (Map.Entry<Long, Integer> entry : furthestRound.entrySet()) {
                Team club = teams.findById(entry.getKey()).orElse(null);
                if (club == null || club.getCompetition() == null) {
                    continue;
                }
                int tier = club.getCompetition().getTier() == null
                        ? 1 : club.getCompetition().getTier();

                double bonus = 0.0;
                if (entry.getKey().equals(champion)) {
                    bonus += domesticCup
                            ? RankingPointsEngine.clubBonus(RankingPointsEngine.NATIONAL_CUP_WINNER, tier)
                            : RankingPointsEngine.clubBonus(RankingPointsEngine.CLUB_CUP_WINNER, tier);
                }
                if (internationalCup) {
                    bonus += RankingPointsEngine.clubBonus(RankingPointsEngine.tournamentPhaseBonus(
                            entry.getValue(), false,
                            RankingPointsEngine.CLUB_CUP_QUALIFIED,
                            RankingPointsEngine.CLUB_CUP_GROUP_STAGE,
                            RankingPointsEngine.CLUB_CUP_ROUND_OF_SIXTEEN,
                            RankingPointsEngine.CLUB_CUP_QUARTER_FINAL,
                            RankingPointsEngine.CLUB_CUP_SEMI_FINAL,
                            RankingPointsEngine.CLUB_CUP_FINAL,
                            RankingPointsEngine.CLUB_CUP_WINNER), tier);
                }
                addTo(clubLedger, club, season, bonus);
                touched++;
            }
        }
        return touched;
    }

    /**
     * The furthest round each team appears in, read from the fixtures.
     *
     * <p>A team in the final appears in the final, so it is in every earlier round too, and this returns
     * the highest. Returns an empty map when the competition has no fixtures, which is the normal state
     * before a tournament is drawn and must not be read as "nobody qualified".
     */
    private Map<Long, Integer> furthestRoundPerTeam(Competition competition, int season) {
        Map<Long, Integer> furthest = new LinkedHashMap<>();
        for (MatchFixture fixture : fixtures
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                        competition.getId(), season)) {
            if (fixture.getRoundNumber() == null) {
                continue;
            }
            note(furthest, fixture.getHomeTeam(), fixture.getRoundNumber());
            note(furthest, fixture.getAwayTeam(), fixture.getRoundNumber());
        }
        return furthest;
    }

    /**
     * The furthest round a team reached, as the highest round number it appears in.
     *
     * <p>Correct for the national tournament, whose rounds are numbered from the round of sixteen
     * upwards ({@code ROUND_LAST_SIXTEEN = 1} ... {@code ROUND_FINAL = 5}), and documented as an
     * approximation elsewhere rather than pretended to be exact.
     *
     * <p>The first version merged a constant {@code 1} with {@code Math::max}, so every team came out as
     * "reached the first round" no matter how far it actually went, and every bonus above that was dead.
     */
    private void note(Map<Long, Integer> furthest, Team side, int round) {
        if (side == null || side.getId() == null) {
            return;
        }
        furthest.merge(side.getId(), round, Math::max);
    }

    /**
     * The winner of the tournament: whoever won the <b>last</b> round.
     *
     * <p>Not the winner of any round. A finalist who won three matches on the way there and lost the
     * final has won nothing to be paid a trophy for, and a version of this that collected every round's
     * winner paid them for it.
     *
     * <p>The last round is the <b>final</b>, and this only works because the national tournament numbers
     * its rounds the way the owner described them: semi-final winners play the final
     * ({@code ROUND_SEMI_FINAL = 3}), and the semi-final losers play each other for third place
     * ({@code ROUND_THIRD_PLACE = 4}) <b>before</b> it ({@code ROUND_FINAL = 5}). So "highest round
     * number" is the final and never the bronze match, which is why that extra round cannot hand the
     * trophy to the side that lost the semi.
     *
     * <p>Null while the last round is undecided, which is the state a competition is in for most of the
     * season and the reason no trophy is paid before it is earned.
     */
    private Long tournamentChampion(Competition competition, int season) {
        Integer lastRound = null;
        Long champion = null;
        for (MatchFixture fixture : fixtures
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                        competition.getId(), season)) {
            if (fixture.getRoundNumber() == null) {
                continue;
            }
            if (lastRound == null || fixture.getRoundNumber() > lastRound) {
                lastRound = fixture.getRoundNumber();
                champion = null;
            }
            if (fixture.getRoundNumber() != lastRound) {
                continue;
            }
            org.example.footballmanager.newLogic.model.Match played = fixture.getPlayedMatch();
            if (played == null) {
                continue;
            }
            Team winner = played.getHomeGoals() > played.getAwayGoals() ? fixture.getHomeTeam()
                    : played.getAwayGoals() > played.getHomeGoals() ? fixture.getAwayTeam() : null;
            if (winner != null && winner.getId() != null) {
                champion = winner.getId();
            }
        }
        return champion;
    }

    /**
     * The teams that won a round, read from the fixtures.
     *
     * <p>A winner is the side with the higher score on the played match. Drawn and unplayed fixtures
     * contribute nobody, so a competition mid-tournament pays only for what has actually been won.
     */
    private Set<Long> winnersOf(Competition competition, int season) {
        Set<Long> winners = new HashSet<>();
        for (MatchFixture fixture : fixtures
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                        competition.getId(), season)) {
            // The fixture carries no score of its own - the result lives on the Match that was played
            // from it - so an unplayed or absent result contributes nobody and a competition mid-tournament
            // pays only for what has actually been won.
            org.example.footballmanager.newLogic.model.Match played = fixture.getPlayedMatch();
            if (played == null) {
                continue;
            }
            Team winner = played.getHomeGoals() > played.getAwayGoals() ? fixture.getHomeTeam()
                    : played.getAwayGoals() > played.getHomeGoals() ? fixture.getAwayTeam() : null;
            if (winner != null && winner.getId() != null) {
                winners.add(winner.getId());
            }
        }
        return winners;
    }

    /**
     * Team id to owning country, built once per pass.
     *
     * <p>This walked every country per side to find the match, which is 96 sides x 48 countries on a
     * full world for a lookup an index of two fields answers outright. The same shape the two replays
     * use, for the same reason.
     */
    private Map<Long, Country> countryBySideId() {
        Map<Long, Country> bySide = new LinkedHashMap<>();
        for (Country country : countries.findAll()) {
            claim(bySide, country, country.getSeniorNationalTeam());
            claim(bySide, country, country.getU21NationalTeam());
        }
        return bySide;
    }

    private void claim(Map<Long, Country> bySide, Country country, Team side) {
        if (side != null && side.getId() != null) {
            bySide.put(side.getId(), country);
        }
    }

    /** Adds a bonus to a club's existing season subtotal, creating the row when there is none. */
    private void addTo(ClubSeasonRankingPointsRepository ledger, Team club, int season, double bonus) {
        if (bonus == 0.0) {
            return;
        }
        ClubSeasonRankingPoints row = ledger.findByTeamIdAndSeasonYear(club.getId(), season)
                .orElseGet(() -> new ClubSeasonRankingPoints(club, season, 0.0));
        // Set, not added. Adding into the same column as the match points meant a second press paid
        // the trophy twice: 210.0 then 420.0.
        row.setBonusPoints(bonus);
        ledger.save(row);
    }

    /** The same, for a country at one level. */
    private void addTo(CountrySeasonRankingPointsRepository ledger, Country country,
                       NationalTeamLevel level, int season, double bonus) {
        if (bonus == 0.0) {
            return;
        }
        CountrySeasonRankingPoints row = ledger
                .findByCountryIdAndLevelAndSeasonYear(country.getId(), level, season)
                .orElseGet(() -> new CountrySeasonRankingPoints(country, level, season, 0.0));
        // Set, not added — see the club version above.
        row.setBonusPoints(bonus);
        ledger.save(row);
    }

    /** What one pass did. */
    public record Result(int nationalRows, int clubRows) {
    }
}