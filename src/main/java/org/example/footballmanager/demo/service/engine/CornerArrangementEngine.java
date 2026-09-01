package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.model.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Corner set-piece arrangement (corePrinciples §48 extensions).
 *
 * When a corner is awarded the attacking team's outfielders get organized in
 * the opponent's box and each defender marks an attacker 1v1 (goal-side). While
 * the corner is being walked/taken everyone jostles 2–4 m around their spot —
 * a real-corner shuffle — and, crucially, the marking stays locked during the
 * delivery flight so the incoming cross finds an aerial duel instead of an
 * uncontested catch.
 *
 * The arrangement is active for a fixed window (walk + delivery + a short
 * resolution tail) and then dissolves back to normal tactical play.
 */
public class CornerArrangementEngine {

    // Attackers gather in the penalty area in front of the goal they are
    // attacking. HOME attacks the AWAY goal at row 8; AWAY attacks the HOME
    // goal at row 1. Five lanes spread across the 6-yard/PK area.
    private static final double[][] HOME_BOX = {
            {6.2, 4.6}, {6.2, 2.4}, {6.7, 3.5}, {5.6, 5.2}, {5.6, 1.8}
    };
    private static final double[][] AWAY_BOX = {
            {1.8, 4.6}, {1.8, 2.4}, {1.3, 3.5}, {2.4, 5.2}, {2.4, 1.8}
    };

    // Random jostle 2–4 m around the assigned spot while the corner is walked.
    private static final double SHUFFLE_MIN = 0.14;   // ~2 m
    private static final double SHUFFLE_MAX = 0.29;   // ~4 m
    // How long (ticks) the marking arrangement survives after the corner is
    // delivered — enough for the flight + a couple of aerial-duel resolutions.
    private static final int RESOLUTION_TICKS = 90;

    private final Random random = new Random(1337L);

    public boolean isCornerArrangementActive(MatchState state) {
        return state.isCornerActive();
    }

    /**
     * @return true if this tick still uses the corner arrangement (and thus the
     *         caller should NOT apply normal tactical targets).
     */
    public boolean applyCornerTargets(MatchState state) {
        if (!state.isCornerActive()) return false;
        // Expire the arrangement a fixed window after the corner is delivered
        // (markCornerDelivered resets the clock at the first-touch). Do NOT
        // expire merely on the taker being null / set-piece cleared — that
        // fires at the exact moment the taker is about to deliver and would
        // dissolve the box before the cross arrives.
        if (state.getSimulationTick() - state.getCornerShuffleTick() > RESOLUTION_TICKS
                && !state.isSetPiecePending()) {
            expire(state);
            return false;
        }

        String attackingTeam = state.getCornerTeam();
        boolean homeAttacking = "HOME".equals(attackingTeam);
        Player taker = state.getFreeKickTaker();

        List<Player> attackers = new ArrayList<>();
        List<Player> defenders = new ArrayList<>();
        for (Player p : state.getPlayers()) {
            if (p.isSentOff() || p.isInjured() || "GK".equals(p.getRole())) continue;
            if (p == taker) continue; // the corner taker is at the flag
            if (p.getTeam().equals(attackingTeam)) attackers.add(p);
            else defenders.add(p);
        }

        double[][] box = homeAttacking ? HOME_BOX : AWAY_BOX;
        Position[] attackerTargets = new Position[attackers.size()];
        for (int i = 0; i < attackers.size(); i++) {
            double[] spot = box[i % box.length];
            attackerTargets[i] = new Position(spot[0], spot[1]);
        }

        boolean[] marked = new boolean[attackers.size()];
        List<Player> unmarked = new ArrayList<>();
        for (Player d : defenders) {
            int best = -1;
            double bestDist = Double.MAX_VALUE;
            for (int a = 0; a < attackers.size(); a++) {
                if (marked[a]) continue;
                double dist = Math.hypot(d.getPosition().getColumn() - attackerTargets[a].getColumn(),
                        d.getPosition().getRow() - attackerTargets[a].getRow());
                if (dist < bestDist) { bestDist = dist; best = a; }
            }
            if (best >= 0) {
                marked[best] = true;
                Position at = attackerTargets[best];
                // Stand goal-side of the attacker: for HOME attacking (goal row 8)
                // the marker sits further toward row 8; for AWAY toward row 1.
                double markerRow = homeAttacking ? at.getRow() + 0.45 : at.getRow() - 0.45;
                double mMin = homeAttacking ? 2.5 : 1.1;
                double mMax = homeAttacking ? 7.9 : 5.5;
                d.setTarget(new Position(clampRow(markerRow, mMin, mMax),
                        clampCol(at.getColumn())));
            } else {
                unmarked.add(d);
            }
        }
        // Remaining defenders hold a defensive line in front of goal.
        for (Player d : unmarked) {
            double row = homeAttacking ? 7.6 : 0.4;
            d.setTarget(new Position(clampRow(row, 1.1, 7.9),
                    clampCol(d.getPosition().getColumn())));
        }

        // Jostle attackers + defenders while the corner is still being walked.
        boolean shuffle = state.isSetPiecePending();
        for (int i = 0; i < attackers.size(); i++) {
            attackers.get(i).setTarget(shuffle
                    ? jostle(attackerTargets[i]) : attackerTargets[i]);
        }

        return true;
    }

    public void markCornerDelivered(MatchState state) {
        // The taker has played the corner — restart the resolution window from
        // this tick so the arrangement survives the flight + short tail.
        state.setCornerShuffleTick(state.getSimulationTick());
    }

    private void expire(MatchState state) {
        state.setCornerActive(false);
    }

    private Position jostle(Position base) {
        double len = SHUFFLE_MIN + random.nextDouble() * (SHUFFLE_MAX - SHUFFLE_MIN);
        double angle = random.nextDouble() * 2.0 * Math.PI;
        double dr = len * Math.sin(angle);
        double dc = len * Math.cos(angle);
        return new Position(clampRow(base.getRow() + dr, 1.1, 7.9),
                clampCol(base.getColumn() + dc));
    }

    private static double clampRow(double row, double min, double max) {
        return Math.max(min, Math.min(max, row));
    }

    private static double clampCol(double col) {
        return Math.max(1.0, Math.min(6.0, col));
    }
}
