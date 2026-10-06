package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.MatchValue;
import org.example.footballmanager.newLogic.model.NationalStage;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.model.NationalTournamentSchedule;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.ScoredMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A country's rating, derived from the internationals it has played (owner, 2026-09-30).
 *
 * <p>{@link RatingEngine} has existed since 2026-09-28 with no caller at all. Every country was written
 * at {@code STARTING_RATING} by the catalogue seeder and nothing ever moved them, so the World page's
 * rating column was real data that could only ever read 1500.
 *
 * <h2>Why this replays the history instead of applying deltas as matches finish</h2>
 *
 * <p>The obvious implementation is {@code rating += delta} in the matchday job, and it is wrong here
 * for three reasons:
 *
 * <ul>
 *   <li><b>It cannot fix a world that has already played.</b> 24 internationals are on the database
 *       now, all scored with every country level. An incremental job leaves the column flat — which is
 *       the exact state this task exists to end — and the owner would have to reset to see a change.</li>
 *   <li><b>It double-applies.</b> It needs a "already rated" flag, and a flag is a place for a
 *       re-run, a restored backup or a replayed fixture to silently rate a match twice.</li>
 *   <li><b>Drift has no floor.</b> Each write is a rounding and a rounding is a permanent change; over
 *       a season the column stops being a function of the results.</li>
 * </ul>
 *
 * <p>Replaying from 1500 every time has none of those failure modes. It is a pure function of the
 * match table, so it is idempotent by construction, it repairs itself after a bad write, and it gives
 * the same answer on every machine. The cost is a replay of a table that is currently 24 rows and
 * will be a few thousand at most, which is nothing.
 *
 * <h2>Senior and youth are rated separately</h2>
 *
 * <p>A country's senior side is {@code Country.seniorNationalTeam} and its under-21s are
 * {@code Country.u21NationalTeam}, so the two never share a column. A twenty-year-old's result is not
 * evidence about the senior national team, and pooling them would let a youth tournament move a
 * country's senior standing.
 */
@Service
public class NationalRatingService {

    private static final Logger log = LoggerFactory.getLogger(NationalRatingService.class);

    /**
     * Every country starts level.
     *
     * <p>The replay begins here, not at whatever the column happens to say. A replay that started from
     * the stored value would ratchet: it could only ever move ratings further apart, never back, and
     * "1500 + 1500" would grow without bound.
     */
    public static final double START_RATING = RatingEngine.NATIONAL_START_RATING;

    private final MatchRepository matches;
    private final MatchFixtureRepository tournamentFixtures;
    private final CountryRepository countries;
    private final CompetitionRepository competitions;
    private final TransactionTemplate requiresNew;

    public NationalRatingService(MatchRepository matches,
                                 MatchFixtureRepository tournamentFixtures,
                                 CountryRepository countries,
                                 CompetitionRepository competitions,
                                 PlatformTransactionManager transactionManager) {
        this.matches = matches;
        this.tournamentFixtures = tournamentFixtures;
        this.countries = countries;
        this.competitions = competitions;
        // A transaction of its own, for {@link #recomputeDurably()} only. See that method.
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** What one run produced, so the log can prove the job moved something. */
    public record Result(int matchesReplayed, int countriesRated, double highest, double lowest) {
    }

    /**
     * Replays the history and writes the ratings, in the caller's transaction.
     *
     * <p>Right for a caller that is going to commit anyway — the matchday job, and the tests. It is
     * <b>not</b> right for the boot listener; see {@link #recomputeDurably()}.
     */
    @Transactional
    public Result recompute() {
        // INTERNATIONAL *and* TOURNAMENT. It read only INTERNATIONAL, so before the national-team
        // competitions existed that was the whole of a national side's football. A country could
        // win a World Cup and its rating would not move by one point, because the replay never
        // looked at the match. The two types are now one history rather than two of them, which is
        // also what keeps the replay idempotent: adding TOURNAMENT does not change how an
        // INTERNATIONAL is rated, it only stops the tournament being invisible.
        List<ScoredMatch> history = matches.findPlayedNationalScoredInOrder();

        // Working ratings, keyed by the side that played them. A country's column is only written at
        // the end, so a half-replayed history never leaves the table holding numbers from no history.
        Map<Long, Double> senior = new HashMap<>();
        Map<Long, Double> youth = new HashMap<>();
        List<Country> world = countries.findAll();
        for (Country country : world) {
            if (country.getSeniorNationalTeam() != null) {
                senior.put(country.getSeniorNationalTeam().getId(), START_RATING);
            }
            if (country.getU21NationalTeam() != null) {
                youth.put(country.getU21NationalTeam().getId(), START_RATING);
            }
        }

        // **Which country owns which national side, answered once.**
        //
        // It used to be `findOwningCountry(teamId)`, which walked `countries.findAll()` — and it was
        // called three times per match: twice from `isNationalSide` and once from `isYouth`. So a replay
        // over N internationals asked the database for the whole world 3N times, having already loaded
        // it into `world` on the line above and thrown that copy away.
        //
        // 96 national sides is a small answer, so it is held in a map for the length of the replay
        // rather than asked for again. `NationalRatingServiceQueryCountTest` pins the count at one query
        // however many matches there are, because the shape of this bug is invisible in a clock: it is a
        // loop inside a loop, over a table with 48 rows, on a machine where the query costs less than
        // the loop that asks for it.
        Map<Long, Country> ownerOfSide = ownerOfSide(world);

        int replayed = 0;
        for (ScoredMatch match : history) {
            if (!isNationalSide(match.homeTeamId(), ownerOfSide)
                    || !isNationalSide(match.awayTeamId(), ownerOfSide)) {
                // A national competition with a club in it is a data problem, not a rating. Skipping it
                // keeps one bad row from corrupting every other country in the world.
                log.warn("International {} has a non-national side ({} v {}); not rated",
                        match.id(), match.homeTeamName() != null ? match.homeTeamName() : "?",
                        match.awayTeamName() != null ? match.awayTeamName() : "?");
                continue;
            }
            applyToWorkingRatings(match, senior, youth, ownerOfSide);
            replayed++;
        }

        // The bonus belongs after every match is applied and before anything is written, so the
        // column only ever holds a completed replay. Applied for both levels: a U-21 nation that
        // reached its own tournament has done the same thing as a senior one, on its own rating.
        for (NationalTeamLevel level : NationalTeamLevel.values()) {
            competitions.findAll().stream()
                    .filter(c -> c.getType() == CompetitionType.TOURNAMENT)
                    .filter(c -> c.getNationalStage() == NationalStage.WORLD_CUP)
                    .filter(c -> c.getNationalLevel() == level)
                    .findFirst()
                    .ifPresent(tournament -> applyQualificationBonus(tournament, senior, youth, ownerOfSide));
        }

        return persist(world, senior, youth, replayed);
    }

    /**
     * Which country owns each national side — senior and U-21 alike, keyed by team id.
     *
     * <p>Membership, not the name, and the same rule {@code isNationalSide} has always used. Built from
     * the countries already in hand, so it costs no query.
     *
     * <p>A senior and a U-21 side colliding on one id would be a corrupt world, and this keeps the first
     * country seen rather than complaining. That is the safe direction: the replay skips a match it
     * cannot attribute instead of rating a side against the wrong country.
     */
    private Map<Long, Country> ownerOfSide(List<Country> world) {
        Map<Long, Country> bySide = new HashMap<>();
        for (Country country : world) {
            if (country.getSeniorNationalTeam() != null) {
                bySide.putIfAbsent(country.getSeniorNationalTeam().getId(), country);
            }
            if (country.getU21NationalTeam() != null) {
                bySide.putIfAbsent(country.getU21NationalTeam().getId(), country);
            }
        }
        return bySide;
    }

    /**
     * The same replay, committed in a transaction of its own.
     *
     * <p><b>This is the one the boot listener must call, and the reason is not subtle.</b> The first
     * version joined the boot transaction and the log reported a healthy
     * {@code Senior range 1506.0–1494.0} over 24 replays — and the database still read 1500 for every
     * one of the 48 countries afterwards. The boot transaction is a long one that does a great deal
     * after this point, and a rating written inside somebody else's transaction is a rating that may
     * never have happened. This is the third time that lesson has cost a session in this codebase; the
     * write now owns its transaction so the log and the table cannot disagree.
     *
     * <p>Costless to call from both places: the replay is a pure function of the match table, so running
     * it twice is the same as running it once.
     */
    public Result recomputeDurably() {
        return requiresNew.execute(status -> recompute());
    }

    private void applyToWorkingRatings(ScoredMatch match, Map<Long, Double> senior,
                                     Map<Long, Double> youth, Map<Long, Country> ownerOfSide) {
        Long homeId = match.homeTeamId();
        Long awayId = match.awayTeamId();
        Map<Long, Double> ratings = isYouth(homeId, ownerOfSide) ? youth : senior;

        double homeRating = ratings.getOrDefault(homeId, START_RATING);
        double awayRating = ratings.getOrDefault(awayId, START_RATING);

        // The stage comes from the competition's own column, never from its name: a World Cup match is
        // worth twice a friendly (the owner), and the only difference between the two rows is that
        // column. Read as OTHER it would be the same as a friendly, which was the state this replay
        // was in for its whole life — the weight existed and no match could ever reach it.
        double k = RatingEngine.nationalK(MatchValue.INTERNATIONAL, match.stage());
        double homeActual = actualFor(match.homeGoals(), match.awayGoals());
        double awayActual = actualFor(match.awayGoals(), match.homeGoals());

        ratings.put(homeId, homeRating + RatingEngine.delta(homeRating, awayRating, homeActual, k));
        ratings.put(awayId, awayRating + RatingEngine.delta(awayRating, homeRating, awayActual, k));
    }

    private Result persist(List<Country> world, Map<Long, Double> senior, Map<Long, Double> youth, int replayed) {
        double highest = Double.NEGATIVE_INFINITY;
        double lowest = Double.POSITIVE_INFINITY;
        int rated = 0;

        for (Country country : world) {
            boolean changed = false;

            Double ownSenior = senior.get(country.getSeniorNationalTeam() == null ? -1L : country.getSeniorNationalTeam().getId());
            if (ownSenior != null) {
                int rounded = (int) Math.round(ownSenior);
                if (country.getReputation() == null || country.getReputation() != rounded) {
                    country.setReputation(rounded);
                    changed = true;
                }
                highest = Math.max(highest, ownSenior);
                lowest = Math.min(lowest, ownSenior);
                rated++;
            }

            Double ownYouth = youth.get(country.getU21NationalTeam() == null ? -1L : country.getU21NationalTeam().getId());
            if (ownYouth != null) {
                int rounded = (int) Math.round(ownYouth);
                if (country.getYouthRating() == null || country.getYouthRating() != rounded) {
                    country.setYouthRating(rounded);
                    changed = true;
                }
            }

            if (changed) {
                countries.save(country);
            }
        }

        Result result = new Result(replayed, rated,
                rated == 0 ? 0.0 : Math.round(highest),
                rated == 0 ? 0.0 : Math.round(lowest));
        if (replayed > 0) {
            log.info("National Elo: replayed {} international(s) over {} side(s). Senior range {}–{}.",
                    replayed, rated, result.highest(), result.lowest());
        }
        return result;
    }

    /**
     * Applies {@link RatingEngine#qualificationBonus()} to every nation that reached the tournament.
     *
     * <p>The owner's rule: <i>"a bonus for qualifying in general",</i> as a separate thing from the
     * value of a match. A nation that grinds through qualifying and narrowly goes out has still done
     * something, and its rating should say so even though it won no knockout match.
     *
     * <p><b>Once, and from the same replay.</b> It is added here rather than in a matchday job because
     * the replay is a pure function of the match table: run it twice and the bonus is added twice,
     * which is the double-application failure this class exists to avoid. Being derived from the
     * tournament's own fixtures rather than a flag also means a restored backup reaches the same
     * answer as the original run.
     *
     * <p>Applied before the final write, and only for sides still in the working map — a qualifier has
     * to have played at least one match to have qualified, so this cannot invent a rating.
     */
    private void applyQualificationBonus(Competition tournament,
                                         Map<Long, Double> senior, Map<Long, Double> youth,
                                         Map<Long, Country> ownerOfSide) {
        if (tournament == null) {
            return;
        }
        List<Long> qualifiers = tournamentQualifiers(tournament);
        double bonus = RatingEngine.qualificationBonus();
        int paid = 0;
        for (Long teamId : qualifiers) {
            Country owner = ownerOfSide.get(teamId);
            if (owner == null) {
                continue;
            }
            Map<Long, Double> ratings = isYouth(teamId, ownerOfSide) ? youth : senior;
            Double rating = ratings.get(teamId);
            if (rating != null) {
                ratings.put(teamId, rating + bonus);
                paid++;
            }
        }
        if (paid > 0) {
            log.info("National Elo: qualification bonus of +{} applied to {} side(s) that reached the {}.",
                    (int) bonus, paid, tournament.getName());
        }
    }

    /**
     * The nations that reached a tournament, read off the round of sixteen.
     *
     * <p>From the fixtures rather than from the draw's own record, for the reason the rest of this
     * class replays instead of incrementing: the fixtures are what happened. A team is in the round
     * of sixteen if it appears in one, which cannot disagree with the draw that made it.
     */
    private List<Long> tournamentQualifiers(Competition tournament) {
        return tournamentFixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                        tournament.getId(), seasonOf(tournament)).stream()
                .filter(f -> f.getRoundNumber() != null
                        && f.getRoundNumber() == NationalTournamentSchedule.ROUND_LAST_SIXTEEN)
                .flatMap(f -> java.util.stream.Stream.of(f.getHomeTeam(), f.getAwayTeam()))
                .filter(java.util.Objects::nonNull)
                .map(Team::getId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * The season a tournament's bracket belongs to, or -1 when it has not been drawn.
     *
     * <p>Read off the tournament's own fixtures rather than asked for, so this class needs no season
     * parameter and a caller cannot pass the wrong one. The replay is season-wide and the bracket is
     * the one thing in it that is not.
     */
    private int seasonOf(Competition tournament) {
        return tournamentFixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                        tournament.getId(), 0).stream()
                .map(MatchFixture::getSeasonYear)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(-1);
    }

    /** 1.0 win, 0.5 draw, 0.0 loss from the point of view of the goals passed in first. */
    private double actualFor(int ownGoals, int opponentGoals) {
        if (ownGoals > opponentGoals) {
            return 1.0;
        }
        return ownGoals == opponentGoals ? 0.5 : 0.0;
    }

    /**
     * Is this side one of a country's national teams?
     *
     * <p>Membership, not the name. The catalogue names them "{country} U-21" for the world page, and
     * matching on a name is how "Serbia U-21 Women" ends up rated as a senior side.
     *
     * <p>A map lookup rather than a walk over {@code countries.findAll()}, which is what this did and
     * which cost three whole-table reads per match.
     */
    private boolean isNationalSide(Long teamId, Map<Long, Country> ownerOfSide) {
        return teamId != null && ownerOfSide.containsKey(teamId);
    }

    private boolean isYouth(Long teamId, Map<Long, Country> ownerOfSide) {
        Country owner = ownerOfSide.get(teamId);
        return owner != null && owner.getU21NationalTeam() != null
                && owner.getU21NationalTeam().getId().equals(teamId);
    }
}
