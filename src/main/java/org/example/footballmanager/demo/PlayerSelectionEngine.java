package org.example.footballmanager.demo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Odgovornost: IZBOR / PRETRAGA IGRACA.
 *
 * Pokriva postojece upite:
 *  - {@link #closestHomeTo(Position)} — najblizi HOME igrac nekoj poziciji
 *  - {@link #nearestHomeTo(Player, int)} — N najblizih HOME igraca, bez datog igraca
 *
 * Pravila izbora su IDENTICNA kao pre refaktora: izbor primaoca pasa ostaje
 * nasumican iz liste 6 najblizih kandidata. Bez taktickog scoringa i bez
 * pametnijeg dodavanja.
 */
public class PlayerSelectionEngine {

    private final SimulationState state;

    public PlayerSelectionEngine(SimulationState state) {
        this.state = state;
    }

    /** Najblizi HOME igrac datoj poziciji. */
    public Player closestHomeTo(Position pos) {
        return closestHomeTo(pos, null);
    }

    /** Najblizi HOME igrac, uz mogucnost da se prethodni Chase igrac izuzme. */
    public Player closestHomeTo(Position pos, Player excluded) {
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player p : state.getPlayers()) {
            if (!SimulationState.TEAM_HOME.equals(p.getTeam())) {
                continue;
            }
            if (p == excluded || p.isLocked() || state.isBlockedAfterDuel(p)) {
                continue;
            }
            double d = MovementEngine.distance(p.getPosition(), pos);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    /** Tokom loose-ball chase-a tačno jedan HOME igrač sme imati ball target. */
    public void clearChaseTargetsExcept(Player chaser) {
        for (Player p : state.getPlayers()) {
            if (SimulationState.TEAM_HOME.equals(p.getTeam()) && p != chaser
                    && p.getTarget() != null) {
                p.setTarget(null);
            }
        }
    }

    public Player closestAwayTo(Position pos) {
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player p : state.getPlayers()) {
            if (!"AWAY".equals(p.getTeam()) || p.isLocked() || state.isBlockedAfterDuel(p)) continue;
            double d = MovementEngine.distance(p.getPosition(), pos);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    public Player closestHomeGoalkeeper() {
        for (Player p : state.getPlayers()) {
            if (SimulationState.TEAM_HOME.equals(p.getTeam()) && "GK".equals(p.getRole())) return p;
        }
        return closestHomeTo(new Position(1, 3.5));
    }

    public Player closestAwayGoalkeeper() {
        for (Player p : state.getPlayers()) {
            if ("AWAY".equals(p.getTeam()) && "GK".equals(p.getRole())) return p;
        }
        return closestAwayTo(new Position(7, 3.5));
    }

    public Player awayByRole(String role) {
        return state.getPlayers().stream()
                .filter(p -> "AWAY".equals(p.getTeam()) && role.equals(p.getRole()))
                .filter(p -> !state.isBlockedAfterDuel(p) && !p.isLocked())
                .findFirst().orElse(null);
    }

    public Player nearestAwayTo(Position pos, boolean excludeGoalkeeper) {
        return nearestAwayTo(pos, excludeGoalkeeper, null);
    }

    public Player nearestAwayTo(Position pos, boolean excludeGoalkeeper, Player excluded) {
        return state.getPlayers().stream()
                .filter(p -> "AWAY".equals(p.getTeam()))
                .filter(p -> !excludeGoalkeeper || !"GK".equals(p.getRole()))
                .filter(p -> p != excluded)
                .filter(p -> !state.isBlockedAfterDuel(p) && !p.isLocked())
                .min(Comparator.comparingDouble(p -> MovementEngine.distance(p.getPosition(), pos)))
                .orElse(null);
    }

    /** N najblizih HOME igraca od date pozicije, bez datog igraca. */
    public List<Player> nearestHomeTo(Player from, int n) {
        List<Player> candidates = new ArrayList<>();
        for (Player p : state.getPlayers()) {
            if (p == from || p.isLocked() || state.isBlockedAfterDuel(p)) {
                continue;
            }
            if (!SimulationState.TEAM_HOME.equals(p.getTeam())) {
                continue;
            }
            candidates.add(p);
        }
        Position fromPos = from.getPosition();
        candidates.sort(Comparator.comparingDouble(p -> MovementEngine.distance(p.getPosition(), fromPos)));
        return candidates.subList(0, Math.min(n, candidates.size()));
    }

    /**
     * EXTENSION POINT za buduci "najbolji izbor" (skills/AI).
     *
     * Buduci sistem ce birati najboljeg kandidata po nekakvoj oceni ("best
     * option", "best receiver"). OVAJ SPRINT: metoda se NE POZIVA — postojece
     * puteve dodavanja i dalje vode {@link #closestHomeTo} / {@link #nearestHomeTo}.
     * Za sada se ponasa identicno kao najblizi igrac, bez ikakvog scoringa.
     */
    public Player selectBestCandidate(Position pos) {
        return closestHomeTo(pos);
    }
}
