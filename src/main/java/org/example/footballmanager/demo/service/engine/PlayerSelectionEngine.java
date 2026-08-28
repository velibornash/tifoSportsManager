package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.model.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Player search/selection engine.
 * Identical logic to demo/PlayerSelectionEngine but using service model.
 */
public class PlayerSelectionEngine {

    private final MatchState state;

    public PlayerSelectionEngine(MatchState state) {
        this.state = state;
    }

    public Player closestHomeTo(Position pos) {
        return closestHomeTo(pos, null);
    }

    public Player closestHomeTo(Position pos, Player excluded) {
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player p : state.getPlayers()) {
            if (!"HOME".equals(p.getTeam())) continue;
            if (p == excluded || p.isLocked() || p.isSentOff() || p.isInjured() || state.isBlockedAfterDuel(p)) continue;
            double d = SimUtils.distance(p.getPosition(), pos);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    public Player closestTeamTo(Position pos, String team) {
        return closestTeamTo(pos, team, null);
    }

    public Player closestTeamTo(Position pos, String team, Player excluded) {
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player player : state.getPlayers()) {
            if (player == excluded || !team.equals(player.getTeam())
                    || player.isLocked() || player.isSentOff() || player.isInjured()
                    || state.isBlockedAfterDuel(player)) continue;
            double distance = SimUtils.distance(player.getPosition(), pos);
            if (distance < bestDistance) {
                best = player;
                bestDistance = distance;
            }
        }
        return best;
    }

    public Player closestOutfieldHomeTo(Position pos) {
        return closestOutfieldTeamTo(pos, "HOME", null);
    }

    public Player closestOutfieldTeamTo(Position pos, String team) {
        return closestOutfieldTeamTo(pos, team, null);
    }

    public Player closestOutfieldTeamTo(Position pos, String team, Player excluded) {
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player player : state.getPlayers()) {
            if (player == excluded || !team.equals(player.getTeam())
                    || "GK".equals(player.getRole())
                    || player.isLocked() || player.isSentOff() || player.isInjured()
                    || state.isBlockedAfterDuel(player)) continue;
            double distance = SimUtils.distance(player.getPosition(), pos);
            if (distance < bestDistance) {
                best = player;
                bestDistance = distance;
            }
        }
        return best;
    }

    public Player closestEligibleActiveChaser(Position ballPos) {
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player chaser : state.getActiveChasers()) {
            if (chaser.isLocked() || chaser.isSentOff() || chaser.isInjured() || state.isBlockedAfterDuel(chaser)) continue;
            double distance = SimUtils.distance(chaser.getPosition(), ballPos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = chaser;
            }
        }
        return best;
    }

    public Player closestEligibleToBall(Position ballPos) {
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player player : state.getPlayers()) {
            if (player.isLocked() || player.isSentOff() || player.isInjured() || state.isBlockedAfterDuel(player)) continue;
            double distance = SimUtils.distance(player.getPosition(), ballPos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    public Player teamByRole(String team, String role) {
        return state.getPlayers().stream()
                .filter(player -> team.equals(player.getTeam()))
                .filter(player -> role.equals(player.getRole()))
                .filter(player -> !state.isBlockedAfterDuel(player) && !player.isLocked()
                        && !player.isSentOff() && !player.isInjured())
                .findFirst().orElse(null);
    }

    public List<Player> nearestTeamTo(Player from, int n) {
        List<Player> candidates = new ArrayList<>();
        for (Player player : state.getPlayers()) {
            if (player == from || !from.getTeam().equals(player.getTeam())
                    || player.isLocked() || state.isBlockedAfterDuel(player)) continue;
            candidates.add(player);
        }
        candidates.sort(Comparator.comparingDouble(player ->
                SimUtils.distance(player.getPosition(), from.getPosition())));
        return candidates.subList(0, Math.min(n, candidates.size()));
    }

    public Player findGoalkeeper(String team) {
        for (Player p : state.getPlayers()) {
            if (team.equals(p.getTeam()) && "GK".equals(p.getRole())
                    && !p.isSentOff() && !p.isInjured()) return p;
        }
        return null;
    }

    public Player closestHomeGoalkeeper() {
        Player found = findGoalkeeper("HOME");
        if (found != null) return found;
        return closestHomeTo(new Position(1, 3.5));
    }

    public Player closestAwayGoalkeeper() {
        Player found = findGoalkeeper("AWAY");
        if (found != null) return found;
        return closestTeamTo(new Position(7, 3.5), "AWAY");
    }

    /**
     * Find the nearest non-goalkeeper player from the given team to a position.
     * Excludes sent-off, injured, and substituted players — but NOT locked players.
     * This is a deliberate semantic difference from closestTeamTo() which also
     * excludes locked/blocked players. Restart takers can be locked.
     */
    public Player nearestNonGoalkeeperTo(Position pos, String teamSide) {
        Player nearest = null;
        double minDist = Double.MAX_VALUE;
        for (Player p : state.getPlayers()) {
            if (!teamSide.equals(p.getTeam())) continue;
            if ("GK".equals(p.getRole())) continue;
            if (p.isSentOff() || p.isInjured() || p.isSubstituted()) continue;
            double dist = SimUtils.distance(p.getPosition(), pos);
            if (dist < minDist) {
                minDist = dist;
                nearest = p;
            }
        }
        return nearest;
    }

    /**
     * Find the goalkeeper for the given team side, regardless of availability.
     * Unlike findGoalkeeper(), this does NOT filter out sent-off or injured players.
     * Used for kickoff takers and free-kick-to-GK scenarios where we need
     * the "original" keeper even if they've been sent off (fallback path).
     */
    public Player anyGoalkeeper(String teamSide) {
        return state.getPlayers().stream()
                .filter(p -> teamSide.equals(p.getTeam()) && "GK".equals(p.getRole()))
                .findFirst().orElse(null);
    }

    /**
     * Goalkeeper defensive-zone bounds — must match TacticalIntentEngine.applyGKAnchor().
     * HOME GK defends goal at row 1, must stay in rows 0.0–2.0.
     * AWAY GK defends goal at row 7, must stay in rows 6.0–8.0.
     * A GK outside these bounds is considered out of position and is
     * skipped when selecting a loose-ball chaser.
     */
    private static final double GK_HOME_ROW_MAX = 2.0;
    private static final double GK_AWAY_ROW_MIN = 6.0;

    /**
     * Check whether a goalkeeper is within their defensive zone and
     * therefore eligible to act as a loose-ball chaser.
     */
    private static boolean isGoalkeeperInDefensiveZone(Player p) {
        if (p == null || !"GK".equals(p.getRole())) return true;
        double row = p.getPosition().getRow();
        if ("HOME".equals(p.getTeam())) return row <= GK_HOME_ROW_MAX;
        if ("AWAY".equals(p.getTeam())) return row >= GK_AWAY_ROW_MIN;
        return true;
    }

    /**
     * Closest HOME player to a position, excluding goalkeepers that have
     * wandered outside their defensive zone. If the GK is in position,
     * normal closest-player logic applies (including the GK).
     */
    public Player closestChaserHomeTo(Position pos) {
        return closestChaserHomeTo(pos, null);
    }

    public Player closestChaserHomeTo(Position pos, Player excluded) {
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player p : state.getPlayers()) {
            if (!"HOME".equals(p.getTeam())) continue;
            if (p == excluded || p.isLocked() || p.isSentOff() || p.isInjured() || state.isBlockedAfterDuel(p)) continue;
            if ("GK".equals(p.getRole()) && !isGoalkeeperInDefensiveZone(p)) continue;
            double d = SimUtils.distance(p.getPosition(), pos);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    /**
     * Closest player from the named team to a position, excluding goalkeepers
     * that have wandered outside their defensive zone. If the GK is in position,
     * normal closest-player logic applies (including the GK).
     */
    public Player closestChaserTeamTo(Position pos, String team) {
        return closestChaserTeamTo(pos, team, null);
    }

    public Player closestChaserTeamTo(Position pos, String team, Player excluded) {
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player player : state.getPlayers()) {
            if (player == excluded || !team.equals(player.getTeam())
                    || player.isLocked() || player.isSentOff() || player.isInjured()
                    || state.isBlockedAfterDuel(player)) continue;
            if ("GK".equals(player.getRole()) && !isGoalkeeperInDefensiveZone(player)) continue;
            double distance = SimUtils.distance(player.getPosition(), pos);
            if (distance < bestDistance) {
                best = player;
                bestDistance = distance;
            }
        }
        return best;
    }
}
