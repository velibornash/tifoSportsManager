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
            if (p == excluded || p.isLocked() || state.isBlockedAfterDuel(p)) continue;
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
                    || player.isLocked() || state.isBlockedAfterDuel(player)) continue;
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
            if (chaser.isLocked() || state.isBlockedAfterDuel(chaser)) continue;
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
            if (player.isLocked() || state.isBlockedAfterDuel(player)) continue;
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
                .filter(player -> !state.isBlockedAfterDuel(player) && !player.isLocked())
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

    public Player closestHomeGoalkeeper() {
        for (Player p : state.getPlayers()) {
            if ("HOME".equals(p.getTeam()) && "GK".equals(p.getRole())) return p;
        }
        return closestHomeTo(new Position(1, 3.5));
    }

    public Player closestAwayGoalkeeper() {
        for (Player p : state.getPlayers()) {
            if ("AWAY".equals(p.getTeam()) && "GK".equals(p.getRole())) return p;
        }
        return closestTeamTo(new Position(7, 3.5), "AWAY");
    }
}
