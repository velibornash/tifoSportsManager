package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.MatchValue;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.ScoredMatch;
import org.example.footballmanager.newLogic.repository.TeamRepository;
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

/**
 * A club's Elo rating, replayed from the football it has played (owner, 2026-10-01).
 *
 * <p>The arithmetic was written first, on 2026-09-28, and had <b>no caller for three days</b> — the
 * same fate as the national side's, where {@code RatingEngine} sat unused until 2026-09-30 and every
 * country in the world read 1500 for ever. What was missing was storage, not mathematics.
 *
 * <h2>Why this replays instead of incrementing</h2>
 *
 * <p>Same three reasons as {@link NationalRatingService}, and the first one is the whole argument:
 *
 * <ul>
 *   <li><b>It cannot fix a world that has already played.</b> Serbia has been playing league matches
 *       since the pyramid went in, all of them scored with every club sitting at its seed. An
 *       incremental job leaves every one of those matches uncounted, and the owner has to reset the
 *       database to see the feature work at all.</li>
 *   <li><b>It double-applies.</b> Incrementing needs an "already rated" flag, and a flag is where a
 *       re-run, a restored backup or a replayed fixture silently rates a match twice.</li>
 *   <li><b>Drift has no floor.</b> Every write is a rounding and a rounding is permanent.</li>
 * </ul>
 *
 * <p>Replaying from the seed has none of them. It is a pure function of the match table and the clubs'
 * divisions, so it is idempotent by construction, it repairs a bad write, and it gives the same answer
 * on every machine. It also means <b>seeding is free</b>: the seed is where the replay begins, so a
 * club is rated from its own tier the first time this runs and there is no separate backfill to forget
 * to call.
 *
 * <h2>Why there is no season reset</h2>
 *
 * <p>None was asked for, and a replay does not want one. Re-seeding every club each season would
 * throw away a season of football; persisting across seasons is what a pure function of the match
 * table gives for nothing. If a reset is ever wanted it should be an explicit owner action, not
 * something the season rollover does behind their back.
 *
 * <h2>What is deliberately missing</h2>
 *
 * <p><b>{@link RatingEngine#qualificationBonus()} is not applied.</b> The owner asked for a bonus for
 * entering a group stage, and there is nothing to detect it from: the international club cups have
 * qualified clubs and <em>no fixtures at all</em>, because the draw is not wired yet, and the national
 * cup is a straight knockout with no group stage. A branch that provably never fires is a green log
 * line and no rating movement, which is the exact failure this codebase keeps being bitten by — so it
 * waits for the draw, and then becomes observable in the same pass that draws it.
 */
@Service
public class ClubRatingService {

    private static final Logger log = LoggerFactory.getLogger(ClubRatingService.class);

    private final MatchRepository matches;
    private final TeamRepository teams;
    private final TransactionTemplate requiresNew;

    public ClubRatingService(MatchRepository matches,
                             TeamRepository teams,
                             PlatformTransactionManager transactionManager) {
        this.matches = matches;
        this.teams = teams;
        // A transaction of its own, for recomputeDurably() only. A rating written inside somebody
        // else's transaction is a rating that may never have happened - the boot listener proved that
        // the hard way for the national ratings.
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * What one run produced, so the log can prove the job moved something.
     *
     * @param clubsRated  every club in the world, because every one is seeded
     * @param clubsMoved  how many ended up somewhere other than their seed — the number that says the
     *                    replay rated real football rather than restating the ladder
     */
    public record Result(int matchesReplayed, int clubsRated, int clubsMoved, double highest, double lowest) {
    }

    /**
     * Replays the football and writes the ratings, in the caller's transaction.
     *
     * <p>Right for a caller that is committing anyway — the match path and the tests. Not right for
     * the boot listener; see {@link #recomputeDurably()}.
     */
    @Transactional
    public Result recompute() {
        List<Team> clubs = teams.findAllClubsWithDivision();

        // Working ratings and the value each club held before its last rated match. Both start at the
        // club's seed, so `previous` is already right for a club that never gets rated: it would then
        // read as its own rating, which is a delta of zero and an honest "nothing has happened yet".
        Map<Long, Double> current = new HashMap<>();
        Map<Long, Double> previous = new HashMap<>();
        for (Team club : clubs) {
            double seed = seedFor(club);
            current.put(club.getId(), seed);
            previous.put(club.getId(), seed);
        }

        int replayed = 0;
        for (ScoredMatch match : matches.findPlayedClubScoredInOrder()) {
            applyToWorkingRatings(match, current, previous);
            replayed++;
        }

        return persist(clubs, current, previous, replayed);
    }

    /**
     * The same replay, committed in a transaction of its own.
     *
     * <p>Costless to call from both places: the replay is a pure function of the match table, so
     * running it twice is the same as running it once.
     */
    public Result recomputeDurably() {
        return requiresNew.execute(status -> recompute());
    }

    /**
     * The seed for one club: tier 1 is 1500 and each tier below is 100 lower (owner, 2026-10-01).
     *
     * <p>A division with no tier on it is treated as tier 1. That is the safe direction: an unknown
     * division should not start its clubs below every known one and then climb out of it over a
     * season of football.
     */
    private double seedFor(Team club) {
        int tier = club.getCompetition() == null || club.getCompetition().getTier() == null
                ? 1
                : club.getCompetition().getTier();
        return RatingEngine.clubStartRating(tier, tier);
    }

    /**
     * Scores one match into the working ratings, recording what both sides held beforehand.
     *
     * <p>The K weight is computed against the gap between the two clubs, which is the whole point of
     * {@code clubK}'s third argument — a fifth-tier club beating a first-tier one is an enormous gain
     * precisely because it exceeded a very low expectation, and that is what the owner meant by
     * "neverovatan rating boost".
     */
    private void applyToWorkingRatings(ScoredMatch match, Map<Long, Double> current, Map<Long, Double> previous) {
        Long homeId = match.homeTeamId();
        Long awayId = match.awayTeamId();
        if (!current.containsKey(homeId) || !current.containsKey(awayId)) {
            // A club with no division is not on the ladder, so there is nothing to rate it against. One
            // such row must not stop the other fourteen thousand being rated. A null id lands here too,
            // which is why this is a check rather than a dereference.
            log.warn("Match {} ({} v {}) has a side with no league division; not rated.",
                    match.id(), match.homeTeamName(), match.awayTeamName());
            return;
        }

        double homeRating = current.get(homeId);
        double awayRating = current.get(awayId);
        double k = RatingEngine.clubK(valueFor(match), homeRating, awayRating);

        double homeActual = actualFor(match.homeGoals(), match.awayGoals());
        double awayActual = actualFor(match.awayGoals(), match.homeGoals());

        previous.put(homeId, homeRating);
        previous.put(awayId, awayRating);
        current.put(homeId, homeRating + RatingEngine.delta(homeRating, awayRating, homeActual, k));
        current.put(awayId, awayRating + RatingEngine.delta(awayRating, homeRating, awayActual, k));
    }

    /**
     * What one match is worth to a rating, from its competition.
     *
     * <p>An international-scope cup is the Champions, Masters or Challenge Cup and takes the heaviest
     * weight; a league match is the reference point everything else is measured against; anything else
     * is a domestic cup. The distinction is {@code scope} and {@code type} rather than the
     * competition's name, because the name is generated and a name-based rule breaks the moment
     * somebody renames a country.
     *
     * <p>The league case is not the leftover branch. It was one, and a test caught it rating a league
     * match at the cup's 0.90 — so a league season moved ratings by nine points fewer than the owner's
     * scale says, in a game whose whole premise is that league football is what a rating is for.
     */
    private MatchValue valueFor(ScoredMatch match) {
        if (match.scope() == null) {
            return MatchValue.LEAGUE;
        }
        if (match.scope() == CompetitionScope.INTERNATIONAL) {
            return MatchValue.INTERNATIONAL;
        }
        return match.type() == CompetitionType.LEAGUE ? MatchValue.LEAGUE : MatchValue.CUP;
    }

    private Result persist(List<Team> clubs, Map<Long, Double> current, Map<Long, Double> previous,
                           int replayed) {
        double highest = Double.NEGATIVE_INFINITY;
        double lowest = Double.POSITIVE_INFINITY;
        int moved = 0;

        for (Team club : clubs) {
            double rating = current.get(club.getId());
            double before = previous.get(club.getId());
            double delta = rating - before;

            boolean changed = !sameValue(club.getEloRating(), rating)
                    || !sameValue(club.getEloPreviousRating(), before)
                    || !sameValue(club.getEloDelta(), delta);
            if (changed) {
                club.setEloRating(round(rating));
                club.setEloPreviousRating(round(before));
                club.setEloDelta(round(delta));
                // The replay keeps full precision and the column is a whole number, so without this the
                // stored value and the computed one would differ by a fraction on every run and every run
                // would report having changed rows it had not.
                teams.save(club);
            }
            if (Math.abs(delta) > 0.5) {
                moved++;
            }
            highest = Math.max(highest, rating);
            lowest = Math.min(lowest, rating);
        }

        Result result = new Result(replayed, clubs.size(), moved,
                clubs.isEmpty() ? 0.0 : Math.round(highest),
                clubs.isEmpty() ? 0.0 : Math.round(lowest));
        if (replayed > 0) {
            log.info("Club Elo: replayed {} club match(es) over {} club(s); {} moved off their seed. "
                            + "Range {}–{}.",
                    replayed, result.clubsRated(), moved, result.highest(), result.lowest());
        } else {
            log.info("Club Elo: no club matches played yet; {} club(s) carry their tier seed ({}–{}).",
                    result.clubsRated(), result.highest(), result.lowest());
        }
        return result;
    }

    /** 1.0 win, 0.5 draw, 0.0 loss from the point of view of the goals passed in first. */
    private double actualFor(int ownGoals, int opponentGoals) {
        if (ownGoals > opponentGoals) {
            return 1.0;
        }
        return ownGoals == opponentGoals ? 0.5 : 0.0;
    }

    /** Elo is published as a whole number, the way a country's is. */
    private double round(double value) {
        return Math.round(value);
    }

    /**
     * Whether the stored column already holds this value.
     *
     * <p><b>This was {@code stored == round(computed)} with {@code round} returning a {@code Double},
     * and that compared two <em>references</em> rather than two numbers.</b> {@link #round} returns a
     * primitive now, so the same line unboxes and compares properly.
     *
     * <p><b>What that bug actually cost, measured rather than assumed:</b> nothing, in SQL terms. The
     * clubs are loaded inside this method's own transaction, so they are managed; {@code save()} on a
     * managed entity is a {@code merge()} that does nothing, and Hibernate skips an UPDATE whose columns
     * are unchanged anyway. Re-running the comparison with the bug present still issues <b>zero</b>
     * UPDATEs — proved by reinstating it and re-running
     * {@code ClubRatingPersistenceTest#aSettledReplayIssuesNoUpdates}, which passes either way.
     *
     * <p>So the claim that this rewrote all ~14,880 clubs after every matchday is <b>not true</b>, and
     * anyone repeating it should measure it first. What was wrong is that the guard above claims to be
     * the thing that avoids needless writes, and it is not — dirty checking is. A guard that does not
     * work is worse than no guard, because the next reader trusts it and stops looking.
     */
    private boolean sameValue(Double stored, double computed) {
        return stored != null && stored == round(computed);
    }
}
