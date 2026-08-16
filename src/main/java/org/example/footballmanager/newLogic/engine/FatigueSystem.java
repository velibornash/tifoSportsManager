package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.model.event.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public final class FatigueSystem {

    private static final Logger log = LoggerFactory.getLogger(FatigueSystem.class);
    private static final Random RNG = new Random();

    private static final double BASE_STAMINA_DRAIN_PER_MINUTE = 0.12;
    private static final double SPRINT_EXTRA_DRAIN = 0.06;
    private static final double INJURY_BASE_CHANCE = 0.00012;
    private static final double INJURY_FATIGUE_MULTIPLIER = 1.5;
    private static final double MAX_FATIGUE = 8.5;

    private final Map<Long, Integer> playerMinutesPlayed = new HashMap<>();
    private final Map<Long, Double> playerFatigueMap = new HashMap<>();
    private final Set<Long> usedSubstitutes = new HashSet<>();

    public FatigueSystem() {}

    public void updateFatigue(MatchState state, int minute) {
        for (PlayerSnapshot snap : state.playerSnapshots) {
            double currentFatigue = playerFatigueMap.getOrDefault(snap.playerId(), 0.0);

            double drain = BASE_STAMINA_DRAIN_PER_MINUTE;
            double staminaFactor = snap.stamina() / 20.0;
            drain *= (1.3 - staminaFactor * 0.6);

            double distMoved = estimateDistanceMoved(state, snap);
            if (distMoved > 2.0) {
                drain += SPRINT_EXTRA_DRAIN * (distMoved / 2.0);
            }

            if (state.carrierId != null && state.carrierId == snap.playerId()) {
                drain += 0.04;
            }

            currentFatigue += drain;
            currentFatigue = Math.min(currentFatigue, MAX_FATIGUE);
            playerFatigueMap.put(snap.playerId(), currentFatigue);

            state.playerFatigue.put(snap.playerId(), (int) currentFatigue);

            int mins = playerMinutesPlayed.getOrDefault(snap.playerId(), 0) + 1;
            playerMinutesPlayed.put(snap.playerId(), mins);
            state.playerMinutes.put(snap.playerId(), mins);
        }
    }

    public void maybeInjury(MatchState state, int minute, String teamSide) {
        for (PlayerSnapshot snap : state.playerSnapshots) {
            if (!snap.teamSide().equals(teamSide)) continue;
            if (state.injuredPlayers.contains(snap.playerId())) continue;

            double fatigue = playerFatigueMap.getOrDefault(snap.playerId(), 0.0);
            double injuryChance = INJURY_BASE_CHANCE;

            if (fatigue > 7.0) {
                injuryChance *= 1.0 + (fatigue - 7.0) * INJURY_FATIGUE_MULTIPLIER;
            }

            double staminaFactor = snap.stamina() / 20.0;
            injuryChance *= (1.5 - staminaFactor * 0.8);

            if (RNG.nextDouble() < injuryChance) {
                state.injuredPlayers.add(snap.playerId());

                state.addEvent(new InjuryEvent(minute, state.tick,
                    snap.playerId(), snap.name(), snap.teamSide()));

                if (state.carrierId != null && state.carrierId == snap.playerId()) {
                    state.carrierId = null;
                    state.carrierTeamSide = null;
                    state.ball = BallState.at(snap.x(), snap.y());
                }
            }
        }
    }

    public void maybeSubstitution(MatchState state, int minute, String teamSide) {
        if (minute < 60) return;
        if (minute % 8 != 0) return;

        int subsUsed = "HOME".equals(teamSide) ? state.homeSubsUsed : state.awaySubsUsed;
        if (subsUsed >= 1) return;

        Team team = "HOME".equals(teamSide) ? state.match.homeTeam() : state.match.awayTeam();
        List<Player> subs = team.substitutes();
        if (subs.isEmpty()) return;

        PlayerSnapshot mostTired = null;
        double maxFatigue = 7.8;

        for (PlayerSnapshot snap : state.playerSnapshots) {
            if (!snap.teamSide().equals(teamSide)) continue;
            if (snap.position() == Position.GK) continue;
            if (state.injuredPlayers.contains(snap.playerId())) continue;

            double fatigue = playerFatigueMap.getOrDefault(snap.playerId(), 0.0);
            if (fatigue > maxFatigue) {
                maxFatigue = fatigue;
                mostTired = snap;
            }
        }

        if (mostTired == null) return;

        Player replacement = findBestSub(subs, mostTired.position());
        if (replacement == null) return;
        usedSubstitutes.add(replacement.id());

        long tiredId = mostTired.playerId();
        String tiredName = mostTired.name();
        state.playerSnapshots.removeIf(s -> s.playerId() == tiredId);
        List<PlayerSnapshot> teammates = state.playerSnapshots.stream()
            .filter(s -> s.teamSide().equals(teamSide)).toList();
        double[] pos = MovementEngine.getStartingPosition(replacement, teamSide, teammates);
        PlayerSnapshot subSnap = PlayerSnapshot.fromPlayer(replacement, teamSide, pos[0], pos[1]);
        // Enter from the sideline and blend to the formation slot — no teleport
        subSnap.setPosition("HOME".equals(teamSide) ? MatchState.MIN_X : MatchState.MAX_X, 50.0);
        state.playerSnapshots.add(subSnap);
        MovementEngine.startBlend(state, replacement.id(), pos[0], pos[1], 90);

        state.playerSlotKeys.put(replacement.id(), state.playerSlotKeys.getOrDefault(tiredId, "CM"));
        playerFatigueMap.put(replacement.id(), 2.0);
        state.playerFatigue.put(replacement.id(), 2);

        state.addEvent(new SubstitutionEvent(minute, state.tick,
            tiredId, tiredName,
            replacement.id(), replacement.name(), teamSide));

        if ("HOME".equals(teamSide)) state.homeSubsUsed++;
        else state.awaySubsUsed++;
    }

    public double getFatigueModifier(long playerId) {
        double fatigue = playerFatigueMap.getOrDefault(playerId, 0.0);
        return Math.max(0.72, 1.0 - Math.max(0, fatigue - 3) * 0.04);
    }

    public double getFatigueValue(long playerId) {
        return playerFatigueMap.getOrDefault(playerId, 0.0);
    }

    private double estimateDistanceMoved(MatchState state, PlayerSnapshot snap) {
        int tick = state.tick;
        if (tick < 120) return 0;
        List<TickSnapshot> history = state.tickHistory;
        if (history.size() < 120) return 0;

        TickSnapshot prev = history.get(Math.max(0, history.size() - 120));
        for (PlayerSnapshot ps : prev.players()) {
            if (ps.playerId() == snap.playerId()) {
                return snap.distanceTo(ps);
            }
        }
        return 0;
    }

    private Player findBestSub(List<Player> subs, Position neededPosition) {
        return subs.stream()
            .filter(p -> !usedSubstitutes.contains(p.id()))
            .filter(p -> p.position() == neededPosition)
            .filter(p -> !p.isInjured())
            .max(Comparator.comparingInt(p -> p.getSkills() != null ? p.getSkills().getStamina() : 0))
            .orElse(subs.stream()
                .filter(p -> !usedSubstitutes.contains(p.id()))
                .filter(p -> !p.isInjured())
                .max(Comparator.comparingInt(p -> p.getSkills() != null ? p.getSkills().getStamina() : 0))
                .orElse(null));
    }
}
