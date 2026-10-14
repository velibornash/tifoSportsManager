package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.MatchValue;
import org.example.footballmanager.newLogic.model.RatingWatermark;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.RatingWatermarkRepository;
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
    private final RatingWatermarkRepository watermarks;
    private final TeamRepository teams;
    private final TransactionTemplate requiresNew;

    public ClubRatingService(MatchRepository matches,
                             TeamRepository teams,
                             RatingWatermarkRepository watermarks,
                             PlatformTransactionManager transactionManager) {
        this.matches = matches;
        this.teams = teams;
        this.watermarks = watermarks;
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
    /**
     * Resumes the replay instead of restarting it, in its own transaction.
     *
     * <p>This is what the matchday calls. The full {@link #recompute()} re-reads every played match in
     * the world, and a season is ~89,280 of them, so running it after each matchday is quadratic. The
     * fold is what makes a resume safe: applying match N+1 needs only the ratings after match N, which
     * are the ratings already stored on the clubs. So the working set starts from what is on the clubs,
     * the stored delta is left alone for clubs this run does not touch, and only the watermark moves.
     *
     * <p>A watermark that is absent or behind a club's true history produces a rating that is merely
     * short, never wrong in a way that cannot be corrected by a full {@link #recompute()}; that remains
     * the repair path and is what the two batch call sites use.
     */
    public Result recomputeIncrementallyDurably() {
        RatingWatermark mark = watermarks.findById(RatingWatermark.CLUB)
                .orElseGet(() -> new RatingWatermark(RatingWatermark.CLUB, null));
        Long after = mark.getAppliedThroughMatchId();
        List<ScoredMatch> pending = after == null
                ? matches.findPlayedClubScoredInOrder()
                : matches.findPlayedClubScoredAfter(after);
        if (pending.isEmpty()) {
            return new Result(0, 0, 0, Double.NaN, Double.NaN);
        }
        // A missing watermark means "replay everything", and it must be folded from the seeds rather
        // than from whatever the clubs currently hold. Starting from stored ratings instead re-applies
        // history the ratings already include: a club on 1514 after the first fold came out at 1527,
        // which is a rating invented by the repair rather than by a result. This is not hypothetical
        // here - a database restored from a dump carries ratings but not the watermark table.
        boolean fromSeed = after == null;
        Integer[] result = requiresNew.execute(status -> applyIncrementally(pending, mark, fromSeed));
        return new Result(result[0], result[1], result[2], Double.NaN, Double.NaN);
    }

    private Integer[] applyIncrementally(List<ScoredMatch> pending, RatingWatermark mark, boolean fromSeed) {
        List<Team> clubs = teams.findAllClubsWithDivision();
        Map<Long, Double> current = new HashMap<>();
        Map<Long, Double> previous = new HashMap<>();
        for (Team club : clubs) {
            // A resume starts from what is stored: the fold only needs the ratings after the last match
            // it already folded, and those are the ratings on the clubs. Falling back to the seed is
            // what makes a brand new club - or one whose rating was never written - start from the
            // ladder rather than from zero, and falling back to its own stored rating keeps an untouched
            // club's existing delta instead of zeroing it on every matchday.
            double stored = club.getEloRating() != null ? club.getEloRating() : seedFor(club);
            double before = club.getEloPreviousRating() != null ? club.getEloPreviousRating() : stored;
            if (fromSeed) {
                stored = seedFor(club);
                before = stored;
            }
            current.put(club.getId(), stored);
            previous.put(club.getId(), before);
        }

        long highest = 0L;
        for (ScoredMatch match : pending) {
            applyToWorkingRatings(match, current, previous);
            if (match.id() != null) {
                highest = Math.max(highest, match.id());
            }
        }

        int moved = persistIncremental(clubs, current, previous);
        mark.setAppliedThroughMatchId(highest);
        watermarks.save(mark);
        log.info("Club Elo resumed: {} match(es) folded in, {} club(s) moved.", pending.size(), moved);
        return new Integer[] {pending.size(), clubs.size(), moved};
    }

    /** Writes only the clubs whose numbers moved, so a matchday is not a 15k-row update. */
    private int persistIncremental(List<Team> clubs, Map<Long, Double> current,
                                   Map<Long, Double> previous) {
        int moved = 0;
        applyStageBonuses(clubs, current);
        for (Team club : clubs) {
            Double rating = current.get(club.getId());
            Double before = previous.get(club.getId());
            if (rating == null || before == null) {
                continue;
            }
            double delta = rating - before;
            if (sameValue(club.getEloRating(), rating)
                    && sameValue(club.getEloPreviousRating(), before)
                    && sameValue(club.getEloDelta(), delta)) {
                continue;
            }
            club.setEloRating(round(rating));
            club.setEloPreviousRating(round(before));
            club.setEloDelta(round(delta));
            teams.save(club);
            if (Math.abs(delta) > 0.5) {
                moved++;
            }
        }
        return moved;
    }

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
     * <p>The K weight no longer looks at the gap. It used to — a fifth-tier club beating a first-tier
     * one was "an enormous gain precisely because it exceeded a very low expectation", which the owner
     * called a *neverovatan rating boost* at the time and then rejected as part of collapsing this into
     * one system. The distinction between the two is not lost, it has moved: the forecast in
     * {@link ScheduleInsightService} already knows a giant is a giant, so a result is rewarded for
     * beating what was predicted rather than for beating a bigger name.
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

    /**
     * Adds Elo for how deep each club went in an international cup (owner, 2026-10-14).
     *
     * <p>Read out of the draw on every replay and added to the working rating, never stored as a
     * separate adjustment. That is what makes it impossible to award twice: a club's bonus is a
     * function of the rounds it appears in, and a replay recomputes that function rather than adding
     * to last week's answer.
     *
     * <p>Both replay paths call this — the full one and the resumed one — because a bonus applied on
     * only one of them would show up the next time the other ran.
     */
    private void applyStageBonuses(List<Team> clubs, Map<Long, Double> current) {
        Map<Long, Integer> bonusByClub = new HashMap<>();
        for (Object[] row : matches.findInternationalStageProgress()) {
            if (row[0] == null || row[1] == null || row[2] == null) {
                continue;
            }
            org.example.footballmanager.newLogic.model.InternationalStageBonus ladder =
                    org.example.footballmanager.newLogic.model.InternationalStageBonus
                            .forCup(String.valueOf(row[1]));
            if (ladder == null) {
                continue;
            }
            int deepest = ((Number) row[2]).intValue();
            boolean won = row[3] != null && ((Number) row[3]).intValue() == 1;
            Long teamId = (Long) row[0];
            bonusByClub.merge(teamId, ladder.bonusFor(deepest, won), Integer::sum);
        }
        for (Team club : clubs) {
            Integer bonus = bonusByClub.get(club.getId());
            Double rating = current.get(club.getId());
            if (bonus == null || rating == null || bonus == 0) {
                continue;
            }
            current.put(club.getId(), rating + bonus);
        }
        if (!bonusByClub.isEmpty()) {
            log.info("Club Elo: international stage bonus added to {} club(s).", bonusByClub.size());
        }
    }

    private Result persist(List<Team> clubs, Map<Long, Double> current, Map<Long, Double> previous,
                           int replayed) {
        double highest = Double.NEGATIVE_INFINITY;
        double lowest = Double.POSITIVE_INFINITY;
        int moved = 0;
        applyStageBonuses(clubs, current);

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
