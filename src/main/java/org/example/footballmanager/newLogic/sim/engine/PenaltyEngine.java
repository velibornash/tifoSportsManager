package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.MatchPhase;
import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.recording.MatchRecorder;
import org.example.footballmanager.newLogic.sim.restarts.RestartManager;
import org.example.footballmanager.newLogic.sim.result.ProposalStatsCollector;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;

/**
 * Penalty kicks (Sprint 1.7).
 *
 * <p>Before this class a penalty was awarded, counted, logged as {@code PENALTY_AWARDED} and then
 * never taken — the taker simply picked the ball up off the spot and the normal final-rows hard-SHOT
 * rule fired, so a penalty was just an 11 m shot with no run-up, no dive and no nerve. The three
 * channels {@code PENALTY_KICK} / {@code PENALTY_SAVED} / {@code PENALTY_MISS} were declared in
 * {@code ActionLogService} and produced by nothing. The award rate was already realistic; only the
 * execution was missing.
 *
 * <h2>Why a penalty is its own mechanic</h2>
 * A penalty is not a shot. Three things are true of a penalty and false of every other shot in this
 * engine, and all three are why the generic path could never be right:
 * <ol>
 *   <li><b>The keeper commits before the kick.</b> He has a dive, not a reaction. He covers one
 *       side of a ~10 m mouth from a standing start, so a well-struck penalty beats a great keeper
 *       far more often than a well-struck shot from 20 m does.</li>
 *   <li><b>The taker picks a side, and the keeper guesses it.</b> This is a mind game with an
 *       explicit read probability, not a geometric proximity test — which is exactly what
 *       {@code GoalkeeperEngine.trySave} models, and why it is not reused here.</li>
 *   <li><b>Placement is precision under pressure.</b> Nerve, not technique-under-congestion.</li>
 * </ol>
 *
 * <h2>Calibration</h2>
 * Real penalties convert ~76%, with ~20% saved and ~4% missed (post/bar/off target). The model below
 * is tuned to land there with average skills and to spread sensibly at the extremes: an elite keeper
 * saves roughly 30% and a poor one roughly 14%, which is the real spread. The numbers were
 * verified with {@code ProposalBatchDiag}; see the S1.7 entry in {@code sprintBacklog.md}.
 *
 * <p><b>No assist is credited</b> on a scored penalty. A penalty has no assister, and the backlog
 * task had said otherwise; crediting one would have invented a completed pass that never happened.
 */
public class PenaltyEngine {

    /** Which side of the goal the ball is going to. */
    public enum Side { LEFT, CENTRE, RIGHT }

    public enum Outcome { SCORED, SAVED, MISSED }

    /** Real-world base rates, before skills. */
    private static final double BASE_SAVE_IF_READ = 0.42;
    private static final double BASE_SAVE_IF_WRONG = 0.06;
    private static final double BASE_READ = 0.33;
    private static final double BASE_MISS = 0.06;

    private static final double SKILL_10 = 10.0;

    private final MatchState state;
    private final MatchRecorder recorder;
    private final ProposalStatsCollector stats;
    private final ActionLogService log;
    private final RestartManager restarts;

    public PenaltyEngine(MatchState state, MatchRecorder recorder, ProposalStatsCollector stats,
                         ActionLogService log, RestartManager restarts) {
        this.state = state;
        this.recorder = recorder;
        this.stats = stats;
        this.log = log;
        this.restarts = restarts;
    }

    /**
     * Picks the man to take it. Real teams hand the ball to their best finisher, not whoever
     * happens to be nearest the spot, so this is a deliberate override of
     * {@code RestartManager.findNearestAttacker}.
     */
    public Player selectTaker(String team) {
        Player best = null;
        double bestScore = -1;
        for (Player p : state.getPlayers()) {
            if (!team.equals(p.getTeam()) || p.isUnavailable()) continue;
            // A goalkeeper never takes a penalty, however good his shooting is. Filtering on
            // position alone let a keeper win the selection whenever the penalty was taken
            // deep in the opposition half, where his row happened to look like an attacker's.
            if (p.getRole() != null && p.getRole().startsWith("GK")) continue;
            double score = p.getSkills().striker() * 1.2 + p.getSkills().technique();
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    /**
     * The result of a penalty, decided before anything is written to the match.
     *
     * @param outcome    scored / saved / missed
     * @param takerSide  where the taker actually put it
     * @param keeperSide where the keeper committed
     * @param read       whether the keeper read the taker correctly
     */
    public record Resolution(Outcome outcome, Side takerSide, Side keeperSide, boolean read) { }

    /**
     * Decides the penalty without touching the match state.
     *
     * <p>Split out from {@link #execute} so the conversion model can be tested as pure arithmetic
     * over thousands of iterations, and so the referee-facing bookkeeping stays in one place.
     */
    public Resolution resolve(Player taker, Player keeper) {
        double takerSkill = penaltySkill(taker);
        double keeperSkill = clampSkill(keeper == null ? SKILL_10 : keeper.getSkills().keeper());

        Side takerSide = chooseSide(taker);
        Side keeperGuess = keeper == null
                ? randomSideOfThree()
                : chooseKeeperGuess(keeper, takerSkill, takerSide);
        boolean read = keeperGuess == takerSide;

        double saveChance = (read
                ? BASE_SAVE_IF_READ + (keeperSkill - SKILL_10) * 0.012
                : BASE_SAVE_IF_WRONG + (keeperSkill - SKILL_10) * 0.004) * strikerPressure(takerSkill);
        double missChance = clamp(BASE_MISS - (takerSkill - SKILL_10) * 0.005, 0.01, 0.10);

        double roll = SimulationRandom.nextDouble();
        Outcome outcome = roll < missChance ? Outcome.MISSED
                : roll < missChance + saveChance ? Outcome.SAVED
                : Outcome.SCORED;
        return new Resolution(outcome, takerSide, keeperGuess, read);
    }

    /**
     * Runs the kick: resolves it, then applies the event, stats and restart consequences.
     */
    public Outcome execute(Player taker, Player keeper) {
        Resolution r = resolve(taker, keeper);
        return apply(taker, keeper, r);
    }

    private Outcome apply(Player taker, Player keeper, Resolution r) {
        double takerSkill = penaltySkill(taker);
        Side takerSide = r.takerSide();
        Side keeperGuess = r.keeperSide();
        boolean read = r.read();

        recorder.appendEvent(state.getMatchTicks(), "PENALTY_KICK",
                "PENALTY taken by " + taker.getLabel()
                        + " (skill " + (int) Math.round(takerSkill) + ") — " + sideName(takerSide),
                taker, null, null, null,
                state.getHomeGoals(), state.getAwayGoals(),
                null, null, null, null, null, null);
        log.log("PEN", "PENALTY_KICK " + taker.getLabel() + " (skill "
                + (int) Math.round(takerSkill) + ") -> " + sideName(takerSide)
                + ", keeper " + (keeper != null ? keeper.getLabel() : "none")
                + " dives " + sideName(keeperGuess) + (read ? " (READ)" : " (wrong way)"));

        // The keeper commits to his dive before the ball is struck. This is the whole point of a
        // penalty: he is not covering the mouth, he has picked a side and been beaten.
        commitDive(keeper, keeperGuess);

        state.setPhase(MatchPhase.SET_PIECE);

        return switch (r.outcome()) {
            case MISSED -> miss(taker);
            case SAVED -> save(taker, keeper);
            case SCORED -> score(taker, keeper);
        };
    }

    /** Scores the penalty and resets for the kickoff, exactly as an open-play goal does. */
    private Outcome score(Player taker, Player keeper) {
        String team = taker.getTeam();
        if ("HOME".equals(team)) state.addHomeGoal();
        else state.addAwayGoal();

        String msg = "*** GOAL " + team + " - PENALTY SCORED by " + taker.getLabel()
                + " (" + taker.getRole() + ") - score "
                + state.getHomeGoals() + ":" + state.getAwayGoals() + " ***";
        log.log("ORC", msg);
        recorder.appendEvent(state.getMatchTicks(), "GOAL", msg, taker, null,
                null, null, state.getHomeGoals(), state.getAwayGoals(),
                null, null, null, null, null, null);
        // assistId is null: a penalty is not an assisted goal.
        stats.onGoal(team, taker.getId(), null);
        stats.onShot(team, taker.getId(), true);

        recorder.appendEvent(state.getMatchTicks(), "PENALTY_SCORED",
                "Penalty converted by " + taker.getLabel(), taker, null,
                null, null, state.getHomeGoals(), state.getAwayGoals(),
                null, null, null, null, null, null);

        String kickoffTeam = "HOME".equals(team) ? "AWAY" : "HOME";
        kickoffAfterPenalty(kickoffTeam);
        return Outcome.SCORED;
    }

    private Outcome save(Player taker, Player keeper) {
        String gkTeam = keeper != null ? keeper.getTeam() : other(taker.getTeam());
        String msg = "PENALTY SAVED by " + (keeper != null ? keeper.getLabel() : "the keeper")
                + " — " + taker.getLabel() + " denied";
        log.log("ORC", msg);
        recorder.appendEvent(state.getMatchTicks(), "PENALTY_SAVED", msg, keeper, taker,
                null, null, state.getHomeGoals(), state.getAwayGoals(),
                null, null, null, null, null, null);
        stats.onSave(gkTeam);
        stats.onShot(taker.getTeam(), taker.getId(), true);
        // Roughly a third of penalties kept out are parried wide rather than held.
        endWithoutGoal(taker, SimulationRandom.nextDouble() < 0.33);
        return Outcome.SAVED;
    }

    private Outcome miss(Player taker) {
        String msg = "PENALTY MISSED by " + taker.getLabel() + " — off target";
        log.log("ORC", msg);
        recorder.appendEvent(state.getMatchTicks(), "PENALTY_MISS", msg, taker, null,
                null, null, state.getHomeGoals(), state.getAwayGoals(),
                null, null, null, null, null, null);
        stats.onShot(taker.getTeam(), taker.getId(), false);
        endWithoutGoal(taker, false);
        return Outcome.MISSED;
    }

    /**
     * Goal awarded, so the match restarts from the centre spot with the conceding team kicking off.
     * Mirrors the open-play goal path in {@code BallResultHandler}.
     */
    private void kickoffAfterPenalty(String kickoffTeam) {
        state.clearPassContext();
        state.setLastShooter(null);
        restarts.handleKickoff(state, kickoffTeam);
        log.log("RST", "kickoff after penalty -> " + kickoffTeam);
    }

    /**
     * A saved or missed penalty ends with the keeper restarting play. A save that is parried wide
     * is a corner, which is what actually happens and is a genuine attacking opportunity for the
     * side that was denied; a clean catch or a shot off target is a goal kick.
     */
    private void endWithoutGoal(Player taker, boolean parriedWide) {
        state.clearPassContext();
        state.setLastShooter(null);
        state.setCarrier(null);
        state.setRestartTaker(null);
        state.clearSetPieceType();
        state.getBall().stop();
        state.setPhase(MatchPhase.OPEN_PLAY);

        String defending = other(taker.getTeam());
        if (parriedWide) {
            restarts.handleRestart(state, "CORNER");
        } else {
            restarts.handleRestart(state, "GOAL_KICK");
        }
        log.log("RST", (parriedWide ? "corner" : "goal kick") + " after penalty, " + defending);
    }

    /**
     * Commits the keeper to a dive. He moves toward the side he guessed, a short way — he cannot
     * cross the whole mouth, which is precisely why guessing wrong concedes the goal.
     */
    private void commitDive(Player keeper, Side guess) {
        if (keeper == null || keeper.isUnavailable()) return;
        Position at = keeper.getPosition();
        double delta = switch (guess) {
            case LEFT -> -0.45;
            case RIGHT -> 0.45;
            case CENTRE -> 0.0;
        };
        double skill = keeper.getSkills().keeper();
        // A better keeper gets further across; a poor one barely moves.
        double reach = 0.30 + (skill / 20.0) * 0.35;
        keeper.setPosition(new Position(at.getRow(), clampCol(at.getColumn() + delta * reach)));
        keeper.setDiveSide(switch (guess) {
            case LEFT -> -1;
            case RIGHT -> 1;
            case CENTRE -> 0;
        });
    }

    /**
     * A penalty taker aims for a corner. Higher technique means he goes for the corner rather than
     * the middle, which is why the keeper's read matters more against a good taker.
     */
    private Side chooseSide(Player taker) {
        double skill = penaltySkill(taker);
        double cornerBias = clamp(0.35 + (skill - SKILL_10) * 0.02, 0.20, 0.70);
        double roll = SimulationRandom.nextDouble();
        if (roll < cornerBias) {
            return SimulationRandom.nextDouble() < 0.5 ? Side.LEFT : Side.RIGHT;
        }
        return Side.CENTRE;
    }

    /**
     * The keeper's guess: does he read the taker correctly?
     *
     * <p>Modelled directly as a read probability rather than by having the keeper roll his own side
     * and comparing, because the second thing to compare against has to be the taker's disguise.
     * A good keeper reads the body shape; a poor one guesses.
     *
     * @param takerSide where the ball is actually going
     */
    private Side chooseKeeperGuess(Player keeper, double takerSkill, Side takerSide) {
        double skill = clampSkill(keeper.getSkills().keeper());
        // A taker who varies his placement is harder to read than one who goes to the same corner.
        double ambush = clamp(1.0 + (takerSkill - SKILL_10) * 0.012, 0.92, 1.12);
        double readChance = clamp(BASE_READ + (skill - SKILL_10) * 0.010, 0.20, 0.55) * ambush;
        if (SimulationRandom.nextDouble() < readChance) return takerSide;
        return wrongSide(takerSide);
    }

    /**
     * A side the taker is definitely NOT using.
     *
     * <p>Deliberately excludes the taker's side. Rolling a fresh uniform side when the read fails
     * silently re-reads the taker one time in three, which turned a 37% read into an effective
     * 58% and pushed saves to 27% — a keeper being beaten far less often than he is in real
     * football. "Guessed wrong" has to mean wrong.
     */
    /** Uniform side, used when there is no keeper at all to model. */
    private static Side randomSideOfThree() {
        return Side.values()[SimulationRandom.nextInt(3)];
    }

    private static Side wrongSide(Side takerSide) {
        Side[] all = Side.values();
        Side candidate = all[SimulationRandom.nextInt(all.length - 1)];
        // nextInt(len-1) indexes 0..len-2; shift past the taker's own side.
        return candidate.ordinal() >= takerSide.ordinal() ? all[candidate.ordinal() + 1] : candidate;
    }

    /** Technique and finishing averaged, with a small composure bonus — the classic taker profile. */
    private double penaltySkill(Player taker) {
        // Clamped to the same 1..20 range every other skill uses: the composure bonus used to
        // hand the best takers a 22, outside the scale the rest of the model is built on.
        return clamp((taker.getSkills().technique() + taker.getSkills().striker()) / 2.0 + 2.0,
                1.0, 20.0);
    }

    /** A strong taker stretches the keeper: shrinks the save chance as skill goes above 10. */
    private double strikerPressure(double takerSkill) {
        return clamp(1.0 - (takerSkill - SKILL_10) * 0.015, 0.85, 1.15);
    }

    private String sideName(Side s) {
        return switch (s) {
            case LEFT -> "keeper's left";
            case CENTRE -> "straight down the middle";
            case RIGHT -> "keeper's right";
        };
    }

    private static String other(String team) {
        return "HOME".equals(team) ? "AWAY" : "HOME";
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clampSkill(double v) {
        return clamp(v, 1.0, 20.0);
    }

    private static double clampCol(double v) {
        return clamp(v, 0.5, 6.5);
    }
}
