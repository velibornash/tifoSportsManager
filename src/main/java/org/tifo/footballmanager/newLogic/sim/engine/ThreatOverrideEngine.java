package org.tifo.footballmanager.newLogic.sim.engine;

import org.tifo.footballmanager.newLogic.sim.model.*;
import org.tifo.footballmanager.newLogic.sim.util.SimUtils;
import java.util.Comparator;

/**
 * Threat override engine — modifies tactical targets when a threat is present.
 *
 * Three behaviors (user requirement 2026-09-14):
 *
 * <h3>TYPE A — Press carrier with ball</h3>
 * <p>When the ball carrier is within RANGE_A of a defender, the defender's
 * target switches from their tactical position to the carrier's position
 * (goal-side press). The defender enters a duel as soon as they are close
 * enough (DuelEngine handles the actual trigger).  This applies EVERYWHERE
 * on the pitch but is especially important in the defender's own half
 * where losing a duel means conceding a shot.</p>
 *
 * <h3>TYPE B — Press dangerous opponent without ball (anticipate)</h3>
 * <p>When an opponent WITHOUT the ball is in the defender's own defensive
 * third and no teammate is within RANGE_B of them, the defender's target
 * switches to a position that closes the opponent down — idea is to be
 * ready if the ball is passed to that opponent.</p>
 *
 * <h3>TYPE C — Offside retreat</h3>
 * <p>An attacker who has been in offside position for OFFSIDE_RETREAT_THRESHOLD
 * consecutive ticks pulls back toward their own goal until clearly onside.
 * Once onside, the counter resets and the player resumes normal tactical
 * positioning.  This simulates a smart attacker learning to time runs better.</p>
 *
 * <p><b>Critical rule:</b> overrides change the TARGET, never the speed.
 * All players are pace-capped at all times — no sprint boosts for pressing.</p>
 *
 * <p>Wired into MatchOrchestrator step 7b (after tactical targets, before
 * movement). TYPE A pressers are closest-eligible-only and pressing-eligible-role
 * only; the press park point lands inside PRESS_DRIB_DUEL_RADIUS, so a
 * press properly ends in a DRIBBLE duel. TYPE C reads consecutiveOffsideCount
 * accumulated by OffsideService.trackOffsidePositions every tick.</p>
 */
public class ThreatOverrideEngine implements EngineInterfaces.ThreatOverrideEngine {

    // --- Tunable constants ---

    /** TYPE A: how far away a carrier can be and still be seen, in cells. One cell — the
     *  owner's "susedna celija", the neighbouring square. Was 1.5 (21 m). */
    public static final double RANGE_A = 1.0;

    /** TYPE B: the same, for a dangerous man without the ball. Was 2.0 (28 m), which is
     *  further than the danger zone itself, so it was never the binding limit. */
    public static final double RANGE_B = 1.0;

    /** How much clear air around a carrier counts as "nobody near him" — the condition for
     *  TYPE A at all, in cells. Lowered from 1.0 to 0.5 so a carrier whose nearest team-mate
     *  sits at the tactical separation distance (0.6) is still considered free and pressable.
     *  A carrier inside half a cell of his own team-mate is already being dealt with. */
    public static final double CARRIER_SPACE_RADIUS = 0.5;

    /** The danger zone measured from our own goal line, in cells. 2.0 = 28 m, which is the
     *  owner's 20-30 m. HOME goal is row 1, AWAY goal is row 8. */
    public static final double DANGER_ZONE_ROW = 2.0;

    /** TYPE C: ticks in offside before retreat begins. */
    public static final int OFFSIDE_RETREAT_THRESHOLD = 3;

    /** TYPE C: how far behind offside line to retreat (cells). */
    public static final double RETREAT_BUFFER = 2.0;

    public ThreatOverrideEngine() {}

    @Override
    public void evaluate(MatchState state) {
        // 1. Clear all threatOverrideActive flags
        for (Player p : state.getPlayers()) {
            p.setThreatOverrideActive(false);
        }

        Player carrier = state.getCarrier();

        // 2. For each non-carrier outfield player (both teams)
        for (Player player : state.getPlayers()) {
            if (player == carrier) continue;
            if (player.isSentOff() || player.isInjured() || player.isLocked()) continue;
            if ("GK".equals(player.getRole())) continue;
            // The designated restart taker keeps his walk-to-ball target — a
            // threat override here would re-route him away from the restart spot
            // and freeze the match on a dead ball (DEAD-WATCH regression).
            if (player == state.getRestartTaker()) continue;

            Position currentTarget = player.getTarget();
            if (currentTarget == null) currentTarget = player.getPosition();
            Position newTarget = currentTarget;

            // a. Try TYPE A: press carrier if within RANGE_A
            Position typeA = pressCarrier(player, state);
            Position typeB = null;
            Position typeC = null;
            if (typeA != null) {
                newTarget = typeA;
                if (state.getActionLogger() != null) {
                    state.getActionLogger().log("THR", "TYPE_A " + player.getLabel() + "(" + player.getRole() + ")"
                            + " presses carrier " + (state.getCarrier() == null ? "none" : state.getCarrier().getLabel())
                            + " at " + state.getActionLogger().p(newTarget));
                }
            } else {
                // b. Try TYPE B: press isolated opponent in own defensive third
                typeB = pressIsolatedOpponent(player, state);
                if (typeB != null) {
                    newTarget = typeB;
                } else {
                    // c. Try TYPE C: offside retreat if consecutiveOffsideCount >= threshold
                    typeC = offsideRetreat(player, state, currentTarget);
                    if (typeC != null) {
                        newTarget = typeC;
                    }
                }
            }

            // 3. Set threatOverrideActive = true for any player whose target was changed
            if (newTarget != currentTarget
                    && (Math.abs(newTarget.getRow() - currentTarget.getRow()) > 1e-9
                    || Math.abs(newTarget.getColumn() - currentTarget.getColumn()) > 1e-9)) {
                player.setTarget(newTarget);
                player.setThreatOverrideActive(true);

                // Shared action logger — every threat override that re-routes a
                // defender writes a trace line (which player pressed who, and why).
                // This is the press→duel path: THR should be followed by a DRIBBLE
                // duel at the press point, never by a silent stuck corridor.
                if (state.getActionLogger() != null) {
                    String which = typeA != null ? "TYPE_A" : typeB != null ? "TYPE_B" : "TYPE_C";
                    String what = state.getCarrier() != null && typeA != null
                            ? state.getCarrier().getLabel() + "(" + state.getCarrier().getRole() + ")"
                            : (typeB != null ? "isolated-opponent-in-danger-zone" : "offside-retreat");
                    state.getActionLogger().log("THR",
                            which + " " + player.getLabel() + "(" + player.getRole() + ")"
                                    + " presses " + what
                                    + " at " + state.getActionLogger().p(newTarget)
                                    + " from " + state.getActionLogger().p(currentTarget));
                }
            }
        }
    }

    // --- TYPE A: Press carrier ---

    /**
     * TYPE A — close down a ball carrier who has space around him (owner, 2026-10-18).
     *
     * <p>The owner's rule, in his words: <i>"kada igrač koji se brani vidi u susednoj celiji igrača
     * protivničke ekipe sa loptom koji nema oko sebe nekoliko metara nikoga — treba da priđe ka njemu
     * na press, sistem rešuje da priđe samo jedan igrač"</i>.
     *
     * <p>Three things changed, and each of them was a defect measured rather than argued:
     *
     * <ol>
     *   <li><b>Any outfield player may press, not only defenders and midfielders.</b> The old
     *       role eligibility test excluded strikers, on the grounds that a striker marking the ball
     *       "looks absurd". But it is the team <em>without</em> the ball that does the pressing, and a
     *       striker thirty metres from his own goal is the closest man to the carrier whenever the
     *       attack goes long. Excluding him did not prevent absurdity; it left the pressing to a
     *       handful of men while nine defenders held a static shape.</li>
     *   <li><b>An adjacent cell, not 1.5.</b> The trigger is now the cell he can see.</li>
     *   <li><b>Only when the carrier is FREE.</b> This is the new and important part, and it inverts
     *       the old behaviour. A carrier with nobody near him is the emergency; a carrier already
     *       surrounded is being dealt with by whoever is closest. Measured over 40 matches the
     *       carrier is inside the old press radius on 97.5% of ticks, so the press was not a
     *       decision at all — it was the permanent state, and a rule that fires on 97.5% of ticks
     *       cannot be steering anything.</li>
     * </ol>
     *
     * <p>Still one presser per carrier: {@link #isClosestEligiblePresser} resolves it, exactly as
     * the owner specified.
     */
    private Position pressCarrier(Player defender, MatchState state) {
        Player carrier = state.getCarrier();
        if (carrier == null) return null;
        if (defender.getTeam().equals(carrier.getTeam())) return null; // don't press own carrier

        double dist = SimUtils.distance(defender.getPosition(), carrier.getPosition());
        if (dist > RANGE_A) return null;

        // "Several metres of nobody around him". A carrier who is already crowded does not need
        // one more man sent at him, and pressing him anyway is what produced a mean of four
        // defenders inside the press radius at the same moment.
        if (!carrierIsFree(state, carrier)) return null;

        if (!isClosestEligiblePresser(carrier, defender, state)) return null;

        // Return the carrier's position — MovementEngine's wall collision
        // (MIN_PLAYER_DISTANCE = 0.35) parks the presser ~0.35-0.4 cells apart,
        // which is inside the presser duel radius (0.38) so the tackle fires.
        return new Position(carrier.getPosition().getRow(), carrier.getPosition().getColumn());
    }

    /** Whether nobody from the carrier's own team is within {@link #CARRIER_SPACE_RADIUS} of him. */
    private boolean carrierIsFree(MatchState state, Player carrier) {
        for (Player teammate : state.getPlayers()) {
            if (!teammate.getTeam().equals(carrier.getTeam())) continue;
            if (teammate.isUnavailable() || teammate == carrier) continue;
            if (SimUtils.distance(teammate.getPosition(), carrier.getPosition()) <= CARRIER_SPACE_RADIUS) {
                return false;
            }
        }
        return true;
    }

    // --- TYPE B: Close down a dangerous opponent without the ball ---

    /**
     * TYPE B — put a man on an opponent who is in a position to hurt us (owner, 2026-10-18).
     *
     * <p>The owner's rule, in his words: <i>"kada igrač koji se brani vidi u susednoj celiji u opasnoj
     * zoni (20-30 m od našeg gola) igrača koji nema loptu ali je u poziciji da ugrozi gol ako je dobije
     * — prilazi ka njemu, sistem rešuje da priđe jedan igrač"</i>.
     *
     * <p><b>The condition this replaces was measuring the wrong thing.</b> It fired only when the
     * opponent was <em>isolated</em>, defined as having no team-mate within half a cell — so the rule
     * that exists to stop a free man in the box switched itself off exactly when the box filled up.
     * Measured: the attacker satisfies that test on 9.2% of samples, and 90.8% of the time a team-mate
     * standing beside him was enough to switch the rule off.
     *
     * <p>The owner replaced it with the question a defender actually asks: is he in a position to
     * hurt us if the ball goes to him? That is <b>goal-side of the ball</b> — on a two-dimensional
     * pitch, a man standing behind the ball cannot be played through, cannot run onto a forward pass,
     * and is not the danger. So:
     *
     * <ul>
     *   <li>in the danger zone — 20-30 m from our own goal, which is where this already fired;</li>
     *   <li>within an adjacent cell of this defender, so a man is close enough to get there;</li>
     *   <li><b>goal-side of the ball</b>, so he is a real target rather than a body in the way;</li>
     *   <li>not already the responsibility of a team-mate — one man per threat, as specified.</li>
     * </ul>
     *
     * <p>Still one player per threat, resolved by {@link #isClosestEligiblePresser}.
     */
    private Position pressIsolatedOpponent(Player defender, MatchState state) {
        boolean home = "HOME".equals(defender.getTeam());

        // **The most dangerous qualifying opponent wins.**
        //
        // This used to return the FIRST opponent that passed the tests below, and the order of the
        // opponent list is the order the players were added to the state — the FORMATION order:
        // goalkeeper, full-backs, centre-backs, midfielders, strikers. So when two attackers
        // qualified, the one who happened to be listed earlier won: a full-back was preferred to a
        // striker, and the choice was an accident of the team factory rather than a football
        // decision.
        //
        // Both candidates are in the danger zone and goal-side of the ball, so the one nearest OUR
        // OWN GOAL LINE is the dangerous one — deepest in our defensive third, the ball behind him
        // and our net in front of him. That is the threat a defender leaves alone at his own peril.
        //
        // "One man per threat" is unchanged: isClosestEligiblePresser still decides WHO goes, the
        // closest man to the threat and only him. This only decides WHICH threat, when a man is
        // close enough to more than one of them.
        Player threat = null;
        double threatDepth = 0;

        for (Player opponent : state.getPlayers()) {
            if (opponent.getTeam().equals(defender.getTeam())) continue;
            if (opponent == state.getCarrier()) continue; // carrier is TYPE A
            if (opponent.isSentOff() || opponent.isInjured() || opponent.isLocked()) continue;
            if (opponent.isGoalkeeper()) continue;

            double opponentRow = opponent.getPosition().getRow();
            // Danger zone = 20-30 m from OUR OWN GOAL. HOME goal line row 1 -> rows
            // <= 3.0; AWAY goal line row 8 -> rows >= 6.0.
            //
            // The zone is measured FROM OUR OWN GOAL LINE, so it has to be measured from the goal
            // line on both sides. It was not: HOME's side used `opponentRow <= DANGER_ZONE_ROW`,
            // which is one cell from the row number rather than two cells from the goal line —
            // **half the intended zone, and outside the 20-30 m band the comment names** — while
            // AWAY's side was measured from its goal line correctly.
            //
            // The two sides were not treated the same, and it showed: over 20 simulated matches,
            // HOME got **464** TYPE B presses and AWAY got **3,410**. Found 2026-10-19.
            boolean inDangerZone = home
                    ? opponentRow <= PitchEnvironment.HOME_GOAL_LINE + DANGER_ZONE_ROW
                    : opponentRow >= PitchEnvironment.AWAY_GOAL_LINE - DANGER_ZONE_ROW;
            if (!inDangerZone) continue;

            double distToOpponent = SimUtils.distance(defender.getPosition(), opponent.getPosition());
            if (distToOpponent > RANGE_B) continue;

            // In a position to threaten our goal if he gets the ball: goal-side of it. A man behind
            // the ball cannot be played through, so he is cover, not a threat, and sending a second
            // defender to him would be one man too many for a job that needs none.
            if (!isGoalSideOfBall(opponent, state.getBall().getPosition(), home)) continue;

            if (!isClosestEligiblePresser(opponent, defender, state)) continue;

            // How far past our own goal line he is. The bigger, the more dangerous.
            double depth = home
                    ? PitchEnvironment.HOME_GOAL_LINE - opponent.getPosition().getRow()
                    : opponent.getPosition().getRow() - PitchEnvironment.AWAY_GOAL_LINE;
            if (threat == null || depth > threatDepth) {
                threat = opponent;
                threatDepth = depth;
            }
        }
        if (threat == null) return null;
        return new Position(threat.getPosition().getRow(), threat.getPosition().getColumn());
    }

    /** Whether a player stands between the ball and the goal his side is attacking. */
    private boolean isGoalSideOfBall(Player attacker, Position ball, boolean attackerIsHome) {
        double ballRow = ball.getRow();
        double row = attacker.getPosition().getRow();
        return attackerIsHome ? row > ballRow + 0.2 : row < ballRow - 0.2;
    }

    // --- TYPE C: Offside retreat ---

    /**
     * If attacker has been offside for OFFSIDE_RETREAT_THRESHOLD consecutive
     * ticks, pull them back toward their own goal until clearly onside.
     * Once onside: reset counter, resume normal tactical targets.
     */
    private Position offsideRetreat(Player attacker, MatchState state, Position tacticalTarget) {
        if (attacker.getConsecutiveOffsideCount() < OFFSIDE_RETREAT_THRESHOLD) return null;

        boolean home = "HOME".equals(attacker.getTeam());

        // Compute offside line (second-to-last defender row)
        String defendingTeam = home ? "AWAY" : "HOME";
        double offsideLineRow = findSecondLastDefenderRow(state, defendingTeam, home);

        // Retreat target: RETREAT_BUFFER cells behind the offside line
        double retreatRow;
        if (home) {
            retreatRow = Math.max(1.0, offsideLineRow - RETREAT_BUFFER);
        } else {
            retreatRow = Math.min(7.9, offsideLineRow + RETREAT_BUFFER);
        }

        // Check if attacker is already clearly onside
        if (isClearlyOnside(attacker, state, defendingTeam, home)) {
            attacker.resetConsecutiveOffside();
            return null; // back to normal tactical target
        }

        // Still offside — return retreat position
        return new Position(retreatRow, tacticalTarget.getColumn());
    }

    // --- Helpers ---

    /**
     * The offside line: the second-to-last defender of the side being attacked.
     *
     * <p><b>The goalkeeper counts.</b> Law 11 says "level with the second-last opponent" — every
     * opponent, the goalkeeper included. The goalkeeper is the deepest defender of the side he
     * defends, so leaving him out moves the line a full cell: for a side defending the low rows it
     * is row 2.5 with him and row 3.0 without, and the attacker retreats to a row half a cell deeper
     * than the law asks for.
     *
     * <p>This filter was the only one of the four copies of this line in the codebase that left the
     * goalkeeper out, and it disagreed with {@link #isClearlyOnside} in this same class — the two
     * halves of one rule. The other three ({@code OffsideService.calculateOffsideMargin}, the rule
     * itself; {@code CleanDecisionEngine.secondLastDefenderRow}, who a pass may be played to; and
     * {@code isClearlyOnside}) all count him. Found 2026-10-19.
     */
    private double findSecondLastDefenderRow(MatchState state, String defendingTeam, boolean home) {
        var rows = state.getPlayers().stream()
                .filter(p -> defendingTeam.equals(p.getTeam()))
                .filter(p -> !p.isSentOff() && !p.isInjured())
                .map(p -> p.getPosition().getRow())
                .sorted(home ? Comparator.<Double>reverseOrder()
                             : Comparator.naturalOrder())
                .toList();
        return rows.size() >= 2 ? rows.get(1) : (home ? 1.0 : 8.0);
    }

    private boolean isClearlyOnside(Player attacker, MatchState state,
                                          String defendingTeam, boolean home) {
        // FIFA offside line: the attacker is onside when at least TWO opponents
        // (including the goalkeeper) are level with or closer to the goal line
        // he is attacking. The retreat ends only then (mirrors the demo/service
        // user rule), then the counter resets and normal tactics resume.
        double playerRow = attacker.getPosition().getRow();
        int opponentsGoalSide = 0;
        for (Player p : state.getPlayers()) {
            if (!defendingTeam.equals(p.getTeam())) continue;
            if (p.isLocked() || p.isSentOff() || p.isInjured()) continue;
            double defRow = p.getPosition().getRow();
            if (home ? defRow > playerRow : defRow < playerRow) {
                opponentsGoalSide++;
            }
        }
        return opponentsGoalSide >= 2;
    }

    /**
     * Return true only for the CLOSEST man to the threat — "the system resolves that only one player
     * comes" (owner, 2026-10-18).
     *
     * <p>This is what stops the rules from turning into a swarm. It used to additionally filter on a
     * role eligibility list, which had a second effect nobody intended: if the closest man to a free
     * carrier was a striker — not on that list — then the candidate returned false, and the press went
     * to the second-closest <em>defender</em> instead. The rule was not "the nearest man presses"; it
     * was "the nearest eligible man presses", and the eligibility list was doing part of the tactical
     * job by accident. With any outfield player eligible, this is simply the nearest man.
     */
    private boolean isClosestEligiblePresser(Player threat, Player candidate, MatchState state) {
        double candidateDistance = SimUtils.distance(candidate.getPosition(), threat.getPosition());
        for (Player teammate : state.getPlayers()) {
            if (teammate == candidate) continue;
            if (!candidate.getTeam().equals(teammate.getTeam())) continue;
            if ("GK".equals(teammate.getRole())) continue;
            if (teammate.isSentOff() || teammate.isInjured() || teammate.isLocked()) continue;
            if (teammate == state.getCarrier()) continue;
            if (teammate == state.getRestartTaker()) continue; // F-TO-3

            double otherDistance = SimUtils.distance(teammate.getPosition(), threat.getPosition());
            if (otherDistance + 1e-9 < candidateDistance) return false;
        }
        return true;
    }
}
